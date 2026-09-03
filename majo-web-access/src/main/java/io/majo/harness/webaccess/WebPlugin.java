package io.majo.harness.webaccess;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/** Mounts {@link WebAccessService} as the {@code web} plugin (no network, no tools). */
public final class WebPlugin implements Plugin {

    public static final String NAME = "web";

    @Override
    public Object apply(Context ctx, Object config) {
        new WebAccessService(ctx);
        return null;
    }

    @Override
    public String name() {
        return NAME;
    }
}
