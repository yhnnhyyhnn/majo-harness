package io.majo.harness.sandbox;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import java.util.List;

/**
 * The sandbox service ({@code ctx.sandbox}): confines an argv list through the
 * configured {@link SandboxProvider} behind the
 * {@link SandboxEvents#PRE_CONFINE} waterfall. Spawner consumers apply
 * {@link #confine} to their argv right before execution.
 */
public final class SandboxService extends Service {

    public static final String NAME = "sandbox";

    private final SandboxProvider provider;

    public SandboxService(Context ctx, SandboxProvider provider) {
        super(ctx, NAME);
        this.provider = provider;
    }

    /** The active confinement provider. */
    public SandboxProvider provider() {
        return provider;
    }

    /** Confines an argv list (validated non-empty, non-null entries). */
    public List<String> confine(List<String> argv) {
        if (argv.isEmpty()) {
            throw new SandboxException("sandbox: cannot confine an empty argv");
        }
        for (String entry : argv) {
            if (entry == null) {
                throw new SandboxException("sandbox: argv must not contain null entries");
            }
        }
        return (List<String>) ctx.waterfall(null, SandboxEvents.PRE_CONFINE, new Object[] {List.copyOf(argv)},
                args -> provider.confine((List<String>) args[0]));
    }
}
