package io.majo.harness.session;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.nio.file.Path;
import java.util.Map;

/**
 * Mounts {@link SessionService} as the {@code session} plugin.
 *
 * <p>Config:
 * <pre>
 * store: memory            # "memory" (default) | "file"
 * path: /var/lib/majo      # directory for the file store; when unset, file
 *                          # stores default to &lt;user.home&gt;/.majo/sessions
 * </pre>
 */
public final class SessionPlugin implements Plugin {

    public static final String NAME = "session";
    public static final String STORE_MEMORY = "memory";
    public static final String STORE_FILE = "file";
    public static final String DEFAULT_FILE_DIR = ".majo/sessions";

    @Override
    public Object apply(Context ctx, Object config) {
        String store = STORE_MEMORY;
        String path = null;
        if (config instanceof Map<?, ?> map) {
            Object storeValue = map.get("store");
            if (storeValue != null) {
                store = String.valueOf(storeValue);
            }
            Object pathValue = map.get("path");
            if (pathValue != null) {
                path = String.valueOf(pathValue);
            }
        }
        SessionStore impl;
        if (STORE_FILE.equals(store)) {
            if (path == null) {
                path = Path.of(System.getProperty("user.home"), DEFAULT_FILE_DIR).toString();
            }
            impl = new FileSessionStore(Path.of(path));
        } else if (STORE_MEMORY.equals(store)) {
            impl = new InMemorySessionStore();
        } else {
            throw new IllegalArgumentException("session plugin: unknown store \"" + store + "\"");
        }
        new SessionService(ctx, impl);
        return null;
    }

    @Override
    public String name() {
        return NAME;
    }
}
