package io.majo.harness.boot.commands;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.Map;

/** Mounts {@link CommandRegistry} as the {@code commands} plugin. */
public final class CommandRegistryPlugin implements Plugin {

    public static final String NAME = CommandRegistry.NAME;

    @Override
    public Object apply(Context ctx, Object config) {
        new CommandRegistry(ctx);
        return null;
    }

    @Override
    public Map<String, Object> inject() {
        return Map.of();
    }

    @Override
    public String name() {
        return NAME;
    }
}
