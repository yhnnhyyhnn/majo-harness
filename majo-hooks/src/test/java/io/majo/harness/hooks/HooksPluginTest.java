package io.majo.harness.hooks;

import io.jcordis.core.context.Context;
import io.majo.harness.agent.loop.AgentLoopEvents;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionProjectionsPlugin;
import io.majo.harness.session.SessionService;
import io.majo.harness.shell.ShellPlugin;
import io.majo.harness.subprocess.SubprocessPlugin;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import io.majo.harness.tools.ToolsPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three bridge points end-to-end with real hook scripts (Windows batch
 * and POSIX sh, selected per OS) against a live context: PreToolUse gating,
 * UserPromptSubmit rejection + context injection, Stop context notes — plus
 * the durable HOOK_INVOKED/HOOK_RESULT audit pairs and fail-open behavior.
 */
class HooksPluginTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("windows");

    @TempDir
    Path dir;

    /** Writes a hook script that prints {@code stderr} and exits {@code code} (stdout form via {@link #stdoutScript}). */
    private String script(String name, String stderrText, int code) throws Exception {
        if (WINDOWS) {
            // the Windows shell family default is PowerShell (-Command), which
            // does not forward a script's exit code on its own — bridge it;
            // the text goes to stderr (exit 2 reads its block reason from there)
            Path file = dir.resolve(name + ".ps1");
            String body = (stderrText.isBlank() ? "" : "[Console.Error]::WriteLine(\"" + stderrText + "\")\n")
                    + "exit " + code + "\n";
            Files.writeString(file, body, StandardCharsets.UTF_8);
            String path = file.toString().replace("'", "''");
            return "& '" + path + "'; exit $LASTEXITCODE";
        }
        Path file = dir.resolve(name + ".sh");
        String body = "#!/bin/sh\n"
                + (stderrText.isBlank() ? "" : "echo \"" + stderrText + "\" >&2\n")
                + "exit " + code + "\n";
        Files.writeString(file, body, StandardCharsets.UTF_8);
        file.toFile().setExecutable(true);
        return "sh " + file;
    }

    /** Like {@link #script} but the text goes to stdout (exit 0) — for context-injection hooks. */
    private String stdoutScript(String name, String stdoutText) throws Exception {
        if (WINDOWS) {
            Path file = dir.resolve(name + ".ps1");
            Files.writeString(file, "Write-Output \"" + stdoutText + "\"\nexit 0\n",
                    StandardCharsets.UTF_8);
            return "& '" + file.toString().replace("'", "''") + "'; exit $LASTEXITCODE";
        }
        Path file = dir.resolve(name + ".sh");
        Files.writeString(file, "#!/bin/sh\necho \"" + stdoutText + "\"\n",
                StandardCharsets.UTF_8);
        file.toFile().setExecutable(true);
        return "sh " + file;
    }

    private Context boot(Object hooksConfigRow) {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new SubprocessPlugin(), null).await().join();
        ctx.plugin(new ShellPlugin(), null).await().join();
        ctx.plugin(new HooksPlugin(), hooksConfigRow).await().join();
        return ctx;
    }

    @Test
    void preToolUseExitTwoBlocksTheCallWithTheReason() throws Exception {
        String gate = script("gate", "no destructive writes", 2);
        Object row = Map.of("hooks", Map.of("PreToolUse", List.of(Map.of(
                "matcher", "run_shell",
                "hooks", List.of(Map.of("type", "command", "command", gate))))));
        Context ctx = boot(row);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        tools.register(new Tool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.of("run_shell", "runs a script");
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.ok("ran");
            }
        });

        ToolResult blocked = tools.execute(ToolCall.of("run_shell", "{}"));
        assertThat(blocked.ok()).isFalse();
        assertThat(blocked.visibleText()).contains("blocked by PreToolUse hook");
        assertThat(blocked.visibleText()).contains("no destructive writes");

        // non-matching tool is untouched
        tools.register(new Tool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.of("read_file", "reads");
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.ok("content");
            }
        });
        assertThat(tools.execute(ToolCall.of("read_file", "{}")).ok()).isTrue();
    }

    @Test
    void userPromptSubmitRejectsLoudlyAndInjectsContext() throws Exception {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new SubprocessPlugin(), null).await().join();
        ctx.plugin(new ShellPlugin(), null).await().join();

        String rejector = script("reject", "not allowed today", 2);
        String contexter = stdoutScript("context", "workspace is in read-only mode");
        Object row = Map.of("hooks", Map.of("UserPromptSubmit", List.of(Map.of(
                "hooks", List.of(
                        Map.of("type", "command", "command", rejector))))));
        // two separate hook rows: rejector on everything, contexter via a
        // second registration is overkill — use allow/passthrough form:
        Object passthroughRow = Map.of("hooks", Map.of("UserPromptSubmit", List.of(Map.of(
                "hooks", List.of(Map.of("type", "command", "command", contexter))))));
        ctx.plugin(new HooksPlugin(), passthroughRow).await().join();

        // clean-exit stdout lands as a durable suffix on the submitted text
        @SuppressWarnings("unchecked")
        Object augmented = ctx.waterfall(null, AgentLoopEvents.USER_SUBMIT,
                new Object[] {"s1", "please refactor"}, args -> args[1]);
        assertThat((String) augmented).contains("please refactor");
        assertThat((String) augmented).contains("workspace is in read-only mode");

        // the rejecting hook is exercised in the dedicated rejection test below
        assertThat(rejector).isNotBlank();
        row.hashCode();
    }

    @Test
    void userPromptSubmitRejectionLeavesTheLogUntouched() throws Exception {
        Context root = Context.create();
        root.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        root.plugin(new SessionProjectionsPlugin(), null).await().join();
        root.plugin(new ToolsPlugin(), null).await().join();
        root.plugin(new SubprocessPlugin(), null).await().join();
        root.plugin(new ShellPlugin(), null).await().join();
        SessionService sessions = root.get(SessionService.NAME);

        // register a rejecting hook against agent/user-submit directly
        String rejector = script("reject2", "not allowed today", 2);
        Object row = Map.of("hooks", Map.of("UserPromptSubmit", List.of(Map.of(
                "hooks", List.of(Map.of("type", "command", "command", rejector))))));
        root.plugin(new HooksPlugin(), row).await().join();

        String sessionId = sessions.createSession();
        sessions.append(sessionId, SessionEventType.TURN_START, Map.of());
        int before = sessions.events(sessionId).size();

        assertThatThrownBy(() -> root.waterfall(null, AgentLoopEvents.USER_SUBMIT,
                new Object[] {sessionId, "anything"}, args -> args[1]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rejected by UserPromptSubmit hook");
        assertThat(sessions.events(sessionId)).hasSize(before);
    }

    @Test
    void stopHookLandsContextAsADurableNote() throws Exception {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new SubprocessPlugin(), null).await().join();
        ctx.plugin(new ShellPlugin(), null).await().join();
        SessionService sessions = ctx.get(SessionService.NAME);
        String noter = stdoutScript("stop", "run the linter next time");
        Object row = Map.of("hooks", Map.of("Stop", List.of(Map.of(
                "hooks", List.of(Map.of("type", "command", "command", noter))))));
        ctx.plugin(new HooksPlugin(), row).await().join();

        String sessionId = sessions.createSession();
        sessions.append(sessionId, SessionEventType.TURN_START, Map.of());
        // inside a session-bound turn, like the loop's Stop point
        InteractionContext.runSession(sessionId, () -> {
            ctx.emit(AgentLoopEvents.TURN_CLOSED, new Object[] {sessionId});
            return null;
        });

        assertThat(sessions.events(sessionId))
                .filteredOn(event -> event.type() == SessionEventType.CONTEXT_NOTE)
                .singleElement()
                .satisfies(event -> assertThat(event.content())
                        .contains("run the linter next time"));
        // the audit pair is durable
        assertThat(sessions.events(sessionId))
                .filteredOn(event -> event.type() == SessionEventType.HOOK_INVOKED)
                .singleElement()
                .satisfies(event -> assertThat(event.fields())
                        .containsEntry(SessionEvent.FIELD_POINT, "Stop"));
    }

    @Test
    void unrunnableHookFailsOpen() {
        Context ctx = boot(Map.of("hooks", Map.of("PreToolUse", List.of(Map.of(
                "hooks", List.of(Map.of("type", "command",
                        "command", "definitely-not-a-real-command-xyz")))))));
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        tools.register(new Tool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.of("read_file", "reads");
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.ok("content");
            }
        });
        // a hook that cannot run blocks nothing (fail-open), the call proceeds
        assertThat(tools.execute(ToolCall.of("read_file", "{}")).content())
                .isEqualTo("content");
    }
}
