package io.majo.harness.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jcordis.core.context.Context;
import io.majo.harness.subprocess.SubprocessException;
import io.majo.harness.subprocess.SubprocessPlugin;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolsPlugin;
import io.majo.harness.tools.ToolResult;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellSeamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    /** Scripts for the platform-default family (PowerShell on Windows, bash elsewhere). */
    private static String script(String... lines) {
        return String.join(WINDOWS ? "\n" : " && ", lines);
    }

    private static String echoScript(String text) {
        return WINDOWS ? "Write-Output " + text : "echo " + text;
    }

    private static ShellService shellService(Context root, Object config) {
        root.plugin(new SubprocessPlugin(), null).await().join();
        root.plugin(new ShellPlugin(), config).await().join();
        return root.get(ShellService.NAME);
    }

    @Test
    void familyFactorySelectsLaunchersAndFailsLoudOnUnknown() {
        ShellFamily detected = ShellFamily.detect();
        assertThat(detected).isNotNull();
        assertThat(detected.launcher().family()).isSameAs(detected);
        assertThat(ShellFamily.ofConfig("bash")).isEqualTo(ShellFamily.BASH);
        assertThat(ShellFamily.ofConfig("pwsh")).isEqualTo(ShellFamily.POWERSHELL);
        assertThat(ShellFamily.ofConfig("cmd")).isEqualTo(ShellFamily.CMD);
        assertThat(ShellFamily.ofConfig(null)).isEqualTo(detected);
        assertThatThrownBy(() -> ShellFamily.ofConfig("zsh"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zsh");
    }

    @Test
    void localProviderRunsScriptsThroughTheSubprocessAdapter(@TempDir Path dir) {
        Context root = Context.create();
        ShellService shell = shellService(root, Map.of("shell", WINDOWS ? "powershell" : "bash"));

        ShellResult ok = shell.run(ShellCommand.of(echoScript("hello-shell")).withTimeoutSeconds(30));
        assertThat(ok.ok()).isTrue();
        assertThat(ok.stdout()).contains("hello-shell");

        ShellResult failing = shell.run(
                ShellCommand.of(WINDOWS ? "exit 7" : "exit 7").withTimeoutSeconds(30));
        assertThat(failing.ok()).isFalse();
        assertThat(failing.exitCode()).isEqualTo(7);

        ShellResult stderr = shell.run(ShellCommand.of(
                WINDOWS ? "Write-Error oops" : "echo oops >&2").withTimeoutSeconds(30));
        assertThat(stderr.stderr()).contains("oops");

        // working directory reaches the process through the adapter
        ShellResult pwd = shell.run(ShellCommand.of(
                WINDOWS ? "Get-Location" : "pwd").withCwd(dir.toString()).withTimeoutSeconds(30));
        assertThat(pwd.stdout()).contains(dir.getFileName().toString());

        root.fiber().disposeAsync().join();
    }

    @Test
    void blankScriptsFailLoudAndPolicyCanReject() {
        Context root = Context.create();
        ShellService shell = shellService(root, Map.of());
        assertThatThrownBy(() -> shell.run(ShellCommand.of("   ").withTimeoutSeconds(30)))
                .isInstanceOf(ShellException.class)
                .hasMessageContaining("blank");

        root.on(ShellEvents.PRE_EXECUTE, (thisArg, args) -> {
            throw new ShellException("denied by policy: " + ((ShellCommand) args[0]).script());
        });
        assertThatThrownBy(() -> shell.run(ShellCommand.of(echoScript("x")).withTimeoutSeconds(30)))
                .isInstanceOf(ShellException.class)
                .hasMessageContaining("denied by policy");
        root.fiber().disposeAsync().join();
    }

    @Test
    void serviceResolvesDefaultTimeoutAndSubprocessFailuresAdapt() {
        Context root = Context.create();
        // 1s default timeout: a long sleep would run forever otherwise
        ShellService shell = shellService(root,
                Map.of("shell", WINDOWS ? "powershell" : "bash", "defaultTimeoutSeconds", 1));
        ShellCommand sleep = ShellCommand.of(WINDOWS
                ? "Start-Sleep -Seconds 5" : "sleep 5");
        assertThatThrownBy(() -> shell.run(sleep))
                .isInstanceOf(ShellException.class)
                .hasMessageContaining("timed out after 1s");
        root.fiber().disposeAsync().join();
    }

    @Test
    void runShellToolConsumesTheSeam() throws Exception {
        Context root = Context.create();
        root.plugin(new ToolsPlugin(), null).await().join();
        shellService(root, Map.of("shell", WINDOWS ? "powershell" : "bash"));
        root.plugin(new ShellToolPlugin(), null).await().join();
        ToolRegistry tools = root.get(ToolRegistry.NAME);
        assertThat(tools.specs()).extracting(spec -> spec.name()).containsExactly("run_shell");

        ToolResult ok = tools.execute(ToolCall.of("run_shell",
                MAPPER.writeValueAsString(Map.of("script", echoScript("tool-out")))));
        assertThat(ok.ok()).isTrue();
        assertThat(ok.content()).isEqualTo("tool-out");

        ToolResult failing = tools.execute(ToolCall.of("run_shell",
                MAPPER.writeValueAsString(Map.of("script", "exit 7"))));
        assertThat(failing.ok()).isFalse();
        assertThat(failing.visibleText()).contains("exited 7");

        ToolResult badArgs = tools.execute(ToolCall.of("run_shell", "{}"));
        assertThat(badArgs.ok()).isFalse();
        assertThat(badArgs.visibleText()).contains("script");

        root.fiber().disposeAsync().join();
    }
}
