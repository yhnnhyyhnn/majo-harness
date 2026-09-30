package io.majo.harness.shell;

import java.util.List;

/**
 * A shell script invocation: a command-line string plus optional working
 * directory, environment overrides, a positive timeout in seconds
 * ({@code 0} defers to the service's configured default at resolve time),
 * and optional text fed to the child's stdin ({@code null} = closed).
 *
 * <p>The script is passed to a {@link ShellLauncher shell family} verbatim —
 * this seam is where command-line syntax enters on purpose, layered strictly
 * over the argv-only subprocess seam.
 */
public record ShellCommand(String script, String cwd, java.util.Map<String, String> env,
        long timeoutSeconds, String stdin) {

    public ShellCommand {
        env = env == null ? java.util.Map.of() : java.util.Map.copyOf(env);
    }

    /** Back-compat shape: no stdin. */
    public ShellCommand(String script, String cwd, java.util.Map<String, String> env,
            long timeoutSeconds) {
        this(script, cwd, env, timeoutSeconds, null);
    }

    /** A script with the service default cwd, env, and timeout. */
    public static ShellCommand of(String script) {
        return new ShellCommand(script, null, java.util.Map.of(), 0);
    }

    public ShellCommand withCwd(String cwd) {
        return new ShellCommand(script, cwd, env, timeoutSeconds, stdin);
    }

    public ShellCommand withTimeoutSeconds(long timeoutSeconds) {
        return new ShellCommand(script, cwd, env, timeoutSeconds, stdin);
    }

    public ShellCommand withStdin(String stdin) {
        return new ShellCommand(script, cwd, env, timeoutSeconds, stdin);
    }

    /** Validates the script before it reaches a provider. */
    public static String requireScript(ShellCommand command) {
        if (command.script() == null || command.script().isBlank()) {
            throw new ShellException("shell: script must not be blank");
        }
        return command.script();
    }

    static List<String> launcherArgs(ShellLauncher launcher, ShellCommand command) {
        return launcher.argv(requireScript(command));
    }
}
