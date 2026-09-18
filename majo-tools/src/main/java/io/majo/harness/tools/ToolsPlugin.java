package io.majo.harness.tools;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/**
 * Mounts {@link ToolRegistry} as the {@code tools} plugin.
 *
 * <p>Config: {@code {toolTimeoutSeconds: <n>}} — the per-tool-call deadline
 * (dsh guard timeout-policy analog); 0 or absent disables it.
 */
public final class ToolsPlugin implements Plugin {

    public static final String NAME = "tools";

    @Override
    public Object apply(Context ctx, Object config) {
        new ToolRegistry(ctx, config);
        return null;
    }

    @Override
    public String name() {
        return NAME;
    }
}
