package io.majo.harness.hooks;

import io.majo.harness.shell.ShellCommand;
import io.majo.harness.shell.ShellResult;
import io.majo.harness.shell.ShellService;

/**
 * Executes command hooks through the shell seam (dsh runner parity): the
 * JSON payload rides stdin with a trailing newline (Claude Code framing), the
 * dialect env entries merge over the ambient environment, and a hook that
 * cannot run is a non-blocking error outcome — never an exception into the
 * turn. Default timeout is the reference's 10 minutes; a per-hook
 * {@code timeout} (seconds) overrides it.
 */
public final class HookRunner {

    /** The reference default per-hook timeout (both dialects). */
    public static final long DEFAULT_TIMEOUT_SECONDS = 600;

    private final ShellService shell;
    private final String workingDirectory;

    public HookRunner(ShellService shell, String workingDirectory) {
        this.shell = shell;
        this.workingDirectory = workingDirectory;
    }

    /**
     * Runs one hook with a JSON payload + dialect env; infrastructure faults
     * become a non-blocking outcome with the message on stderr.
     */
    public HookOutput run(HooksConfig.CommandHook hook, Object payload,
            java.util.Map<String, String> env) {
        String stdin;
        try {
            stdin = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(payload) + "\n";
        } catch (Exception e) {
            return HookOutput.of(null, "", "hooks: cannot encode payload: " + e.getMessage());
        }
        ShellCommand command = ShellCommand.of(hook.command())
                .withStdin(stdin)
                .withTimeoutSeconds(hook.timeoutSeconds() > 0
                        ? hook.timeoutSeconds() : DEFAULT_TIMEOUT_SECONDS);
        if (workingDirectory != null) {
            command = command.withCwd(workingDirectory);
        }
        if (env != null && !env.isEmpty()) {
            java.util.Map<String, String> merged = new java.util.LinkedHashMap<>(command.env());
            merged.putAll(env);
            command = new ShellCommand(command.script(), command.cwd(), merged,
                    command.timeoutSeconds(), command.stdin());
        }
        try {
            ShellResult result = shell.run(command);
            return HookOutput.of(result.exitCode(), result.stdout(), result.stderr());
        } catch (RuntimeException e) {
            // a hook that cannot run is a non-blocking error: the turn proceeds
            return HookOutput.of(null, "", e.getMessage());
        }
    }
}
