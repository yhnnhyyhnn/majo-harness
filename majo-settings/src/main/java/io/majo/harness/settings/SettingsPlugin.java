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
            path = expandHome(String.valueOf(map.get("path")));
        }
        new SettingsService(ctx, path);
        return null;
    }

    /** Expands a leading ~/ against user.home. */
    private static Path expandHome(String value) {
        String home = System.getProperty("user.home");
        if ("~".equals(value)) {
            return Path.of(home);
        }
        if (value.startsWith("~/")) {
            return Path.of(home, value.substring(2));
        }
        return Path.of(value);
    }

    @Override
    public String name() {
        return NAME;
    }
}
