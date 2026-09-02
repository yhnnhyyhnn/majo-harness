package io.majo.harness.settings;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.nio.file.Path;
import java.util.Map;

/**
 * Mounts {@link SettingsService} as the {@code settings} plugin. Config:
 * {@code {path: <file>}} enables the JSON file provider; without a path the
 * store is in-memory only.
 */
public final class SettingsPlugin implements Plugin {

    public static final String NAME = "settings";

    @Override
    public Object apply(Context ctx, Object config) {
        Path path = null;
        if (config instanceof Map<?, ?> map && map.get("path") != null) {
            path = Path.of(String.valueOf(map.get("path")));
        }
        new SettingsService(ctx, path);
        return null;
    }

    @Override
    public String name() {
        return NAME;
    }
}
