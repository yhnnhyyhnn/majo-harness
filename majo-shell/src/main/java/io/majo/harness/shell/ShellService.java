package io.majo.harness.shell;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;

/**
 * The shell service ({@code ctx.shell}) — a narrow facade over the shell
 * world: {@link #run} resolves default timeouts explicitly, routes through the
 * {@link ShellEvents#PRE_EXECUTE} waterfall for policy, and delegates to the
 * configured {@link ShellProvider}.
 *
 * <p>Config: {@code {shell: bash|powershell|cmd, defaultTimeoutSeconds: <n>}}.
 */
public final class ShellService extends Service {

    public static final String NAME = "shell";
    public static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private final ShellProvider provider;
    private final long defaultTimeoutSeconds;

    public ShellService(Context ctx, ShellProvider provider, Object config) {
        super(ctx, NAME);
        this.provider = provider;
        long timeout = DEFAULT_TIMEOUT_SECONDS;
        if (config instanceof java.util.Map<?, ?> map && map.get("defaultTimeoutSeconds") instanceof Number number) {
            timeout = number.longValue();
        }
        if (timeout < 1) {
            throw new IllegalArgumentException("shell: defaultTimeoutSeconds must be >= 1, got " + timeout);
        }
        this.defaultTimeoutSeconds = timeout;
    }

    /** Runs a script through the {@code shell/pre-execute} waterfall. */
    public ShellResult run(ShellCommand command) {
        ShellCommand resolved = command.timeoutSeconds() > 0 ? command
                : new ShellCommand(command.script(), command.cwd(), command.env(), defaultTimeoutSeconds);
        return (ShellResult) ctx.waterfall(null, ShellEvents.PRE_EXECUTE, new Object[] {resolved},
                args -> provider.run((ShellCommand) args[0]));
    }
}
