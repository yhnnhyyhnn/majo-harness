package io.majo.example.template;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.Map;

/**
 * Scaffolded web plugin. Replace the package/class names as you like, add
 * services/tools to {@code apply}, and ship a static frontend under
 * {@code static-web/__name__/}. Start with:
 *
 * <pre>
 *   java -jar majo-web-0.1.0-SNAPSHOT.jar --profile web-mock \
 *     --plugin __name__=./examples/__name__-plugin/__name__.jar
 * </pre>
 *
 * The UI lists the plugin under the sidebar "Plugins" section; opening it
 * shows the hosted page and the native {@code plugin.mjs} module registers its
 * own sidebar contributions.
 */
public final class TemplatePlugin implements Plugin {

    public static final String NAME = "__name__";

    @Override
    public Object apply(Context ctx, Object config) {
        // mount capabilities on ctx (services/tools/providers) or return null
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
