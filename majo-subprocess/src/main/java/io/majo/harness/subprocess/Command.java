package io.majo.harness.subprocess;

import java.util.List;
import java.util.Map;

/**
 * A subprocess invocation: an argv list (executable plus arguments — never a
 * shell command line, so no shell interpolation happens at this seam),
 * optional working directory, environment overrides, a positive timeout in
 * seconds ({@code 0} defers to the service's configured default at resolve
 * time), and optional text fed to the child's stdin ({@code null} = closed;
 * consumers that read stdin, like hooks, receive it verbatim).
 */
public record Command(List<String> argv, String cwd, Map<String, String> env,
        long timeoutSeconds, String stdin) {

    public Command {
        argv = List.copyOf(argv);
        env = env == null ? Map.of() : Map.copyOf(env);
    }

    /** Back-compat shape: no stdin. */
    public Command(List<String> argv, String cwd, Map<String, String> env, long timeoutSeconds) {
        this(argv, cwd, env, timeoutSeconds, null);
    }

    /** A command with the service default working directory, env, and timeout. */
    public static Command of(List<String> argv) {
        return new Command(argv, null, Map.of(), 0);
    }

    /** A command from an argv array. */
    public static Command of(String... argv) {
        return new Command(List.of(argv), null, Map.of(), 0);
    }

    public Command withCwd(String cwd) {
        return new Command(argv, cwd, env, timeoutSeconds, stdin);
    }

    public Command withTimeoutSeconds(long timeoutSeconds) {
        return new Command(argv, cwd, env, timeoutSeconds, stdin);
    }

    public Command withStdin(String stdin) {
        return new Command(argv, cwd, env, timeoutSeconds, stdin);
    }
}
