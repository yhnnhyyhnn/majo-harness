package io.majo.harness.subprocess;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/** Mounts {@link SubprocessService} as the {@code subprocess} plugin. */
public final class SubprocessPlugin implements Plugin {

    public static final String NAME = "subprocess";

    @Override
    public Object apply(Context ctx, Object config) {
        new SubprocessService(ctx, new LocalSubprocessProvider(), config);
        return null;
    }

    @Override
    public String name() {
        return NAME;
    }
}
