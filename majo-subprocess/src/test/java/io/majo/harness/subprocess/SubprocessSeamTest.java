package io.majo.harness.subprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jcordis.core.context.Context;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubprocessSeamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private static Command echo(String text) {
        Command command = WINDOWS ? Command.of("cmd", "/c", "echo", text) : Command.of("/bin/echo", text);
        return command.withTimeoutSeconds(30);
    }

    private static Command sleepCommand(int seconds) {
        return WINDOWS
                ? Command.of("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds " + seconds)
                : Command.of("/bin/sh", "-c", "sleep " + seconds);
    }

    @Test
    void localProviderCapturesOutputAndExitCode() {
        ProcessResult result = new LocalSubprocessProvider().run(echo("hello world"));
        assertThat(result.ok()).isTrue();
        assertThat(result.stdout()).contains("hello world");

        Command failing = (WINDOWS ? Command.of("cmd", "/c", "exit 7") : Command.of("/bin/sh", "-c", "exit 7"))
                .withTimeoutSeconds(30);
        ProcessResult failed = new LocalSubprocessProvider().run(failing);
        assertThat(failed.ok()).isFalse();
        assertThat(failed.exitCode()).isEqualTo(7);

        Command stderr = (WINDOWS
                ? Command.of("cmd", "/c", "echo oops 1>&2")
                : Command.of("/bin/sh", "-c", "echo oops 1>&2")).withTimeoutSeconds(30);
        assertThat(new LocalSubprocessProvider().run(stderr).stderr()).contains("oops");
    }

    @Test
    void localProviderHonoursWorkingDirectory(@TempDir Path dir) {
        Command pwd = (WINDOWS ? Command.of("cmd", "/c", "cd") : Command.of("/bin/pwd"))
                .withTimeoutSeconds(30);
        ProcessResult result = new LocalSubprocessProvider().run(pwd.withCwd(dir.toString()));
        assertThat(result.stdout()).contains(dir.getFileName().toString());
    }

    @Test
    void localProviderFailsLoudOnMissingExecutableAndTimeout() {
        LocalSubprocessProvider provider = new LocalSubprocessProvider();
        assertThatThrownBy(() -> provider.run(Command.of("no-such-binary-xyz-123").withTimeoutSeconds(30)))
                .isInstanceOf(SubprocessException.class)
                .hasMessageContaining("cannot start");

        assertThatThrownBy(() -> provider.run(sleepCommand(5).withTimeoutSeconds(1)))
                .isInstanceOf(SubprocessException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void serviceAppliesDefaultTimeoutAndPolicyEvents() {
        Context root = Context.create();
        root.plugin(new SubprocessPlugin(), Map.of("defaultTimeoutSeconds", 1)).await().join();
        SubprocessService subprocess = root.get(SubprocessService.NAME);

        // an omitted command timeout resolves to the configured default (1s):
        // the 5s sleep would run forever otherwise
        assertThatThrownBy(() -> subprocess.run(sleepCommand(5)))
                .isInstanceOf(SubprocessException.class)
                .hasMessageContaining("timed out after 1s");

        assertThat(subprocess.run(echo("ok")).stdout()).contains("ok");

        root.on(SubprocessEvents.PRE_EXECUTE, (thisArg, args) -> {
            throw new SubprocessException("denied by policy: " + ((Command) args[0]).argv().get(0));
        });
        assertThatThrownBy(() -> subprocess.run(echo("blocked")))
                .isInstanceOf(SubprocessException.class)
                .hasMessageContaining("denied by policy");
        root.fiber().disposeAsync().join();
    }

    @Test
    void runCommandToolConsumesTheSeam() throws Exception {
        Context root = Context.create();
        root.plugin(new io.majo.harness.tools.ToolsPlugin(), null).await().join();
        root.plugin(new SubprocessPlugin(), null).await().join();
        root.plugin(new SubprocessToolPlugin(), null).await().join();
        ToolRegistry tools = root.get(ToolRegistry.NAME);
        assertThat(tools.specs()).extracting(spec -> spec.name()).containsExactly("run_command");

        List<String> argv = WINDOWS
                ? List.of("cmd", "/c", "echo", "tool-out")
                : List.of("/bin/echo", "tool-out");
        ToolResult ok = tools.execute(ToolCall.of("run_command",
                MAPPER.writeValueAsString(Map.of("argv", argv))));
        assertThat(ok.ok()).isTrue();
        assertThat(ok.content()).isEqualTo("tool-out");
        assertThat(ok.data()).containsEntry("exitCode", 0);

        List<String> failing = WINDOWS
                ? List.of("cmd", "/c", "exit 7")
                : List.of("/bin/sh", "-c", "exit 7");
        ToolResult failed = tools.execute(ToolCall.of("run_command",
                MAPPER.writeValueAsString(Map.of("argv", failing))));
        assertThat(failed.ok()).isFalse();
        assertThat(failed.visibleText()).contains("exited 7");
        assertThat(failed.data()).containsEntry("exitCode", 7);

        ToolResult badArgs = tools.execute(ToolCall.of("run_command", "{\"argv\":\"oops\"}"));
        assertThat(badArgs.ok()).isFalse();
        assertThat(badArgs.visibleText()).contains("argv");

        root.fiber().disposeAsync().join();
    }
}
