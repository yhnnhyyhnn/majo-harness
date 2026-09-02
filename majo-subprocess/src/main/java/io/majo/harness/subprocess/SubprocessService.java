package io.majo.harness.subprocess;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;

/**
 * The subprocess service ({@code ctx.subprocess}): delegates to a
 * {@link SubprocessProvider} behind the {@link SubprocessEvents#PRE_EXECUTE}
 * waterfall, so policy plugins participate in every run. A command without an
 * explicit timeout is resolved against the configured default (config
 * {@code {defaultTimeoutSeconds: <n>}}, default 60) — the explicit resolve
 * step, never a hidden default inside the provider.
 */
public final class SubprocessService extends Service {

    public static final String NAME = "subprocess";
    public static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private final SubprocessProvider provider;
    private final long defaultTimeoutSeconds;

    public SubprocessService(Context ctx) {
        this(ctx, new LocalSubprocessProvider(), null);
    }

    public SubprocessService(Context ctx, SubprocessProvider provider, Object config) {
        super(ctx, NAME);
        this.provider = provider;
        long timeout = DEFAULT_TIMEOUT_SECONDS;
        if (config instanceof java.util.Map<?, ?> map && map.get("defaultTimeoutSeconds") instanceof Number number) {
            timeout = number.longValue();
        }
        if (timeout < 1) {
            throw new IllegalArgumentException("subprocess: defaultTimeoutSeconds must be >= 1, got " + timeout);
        }
        this.defaultTimeoutSeconds = timeout;
    }

    /** Runs a command through the {@code subprocess/pre-execute} waterfall. */
    public ProcessResult run(Command command) {
        Command resolved = command.timeoutSeconds() > 0 ? command
                : new Command(command.argv(), command.cwd(), command.env(), defaultTimeoutSeconds);
        return (ProcessResult) ctx.waterfall(null, SubprocessEvents.PRE_EXECUTE, new Object[] {resolved},
                args -> provider.run((Command) args[0]));
    }
}
