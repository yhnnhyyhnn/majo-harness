package io.majo.harness.tools;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/** Mounts {@link ToolRegistry} as the {@code tools} plugin. */
public final class ToolsPlugin implements Plugin {

    public static final String NAME = "tools";

    @Override
    public Object apply(Context ctx, Object config) {
        new ToolRegistry(ctx);
        return null;
    }

    @Override
    public String name() {
        return NAME;
    }
}
