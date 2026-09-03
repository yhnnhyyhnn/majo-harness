package io.majo.example.plugin;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.Map;

/**
 * Minimal web-plugin demo: backend SPI plugin that mounts nothing but ships a
 * static frontend under {@code static-web/web-demo/} inside the same jar.
 * Start with:
 *
 * <pre>
 *   java -jar majo-web-0.1.0-SNAPSHOT.jar --profile web-mock --plugin web-demo=./examples/web-plugin-demo/web-demo.jar
 * </pre>
 *
 * The UI lists the plugin under the sidebar "Plugins" section; opening it
 * shows the hosted page (served from the jar at {@code /plugins/web-demo/}).
 */
public final class WebDemoPlugin implements Plugin {

    public static final String NAME = "web-demo";

    @Override
    public Object apply(Context ctx, Object config) {
        // pure frontend plugin: nothing to mount server-side yet
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
