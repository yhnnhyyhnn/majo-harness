package io.majo.harness.webaccess;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import java.util.HashMap;
import java.util.Map;

/**
 * Registers the Wikipedia no-key search backend ({@code web-search-wiki}).
 * Optional config: {@code {lang: "en"|"de"|…}} for the wiki language edition.
 * The backend is mounted lazily — profile boot never needs the network, only
 * an actual search does.
 */
public final class WikiSearchPlugin implements Plugin {

    public static final String NAME = "web-search-wiki";

    @Override
    public Object apply(Context ctx, Object config) {
        WebAccessService web = ctx.get(WebAccessService.NAME);
        String lang = "en";
        if (config instanceof Map<?, ?> map && map.get("lang") != null) {
            lang = String.valueOf(map.get("lang"));
        }
        Disposable registration = web.registerSearchProvider(new WikiSearchProvider(lang));
        return registration;
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(WebAccessService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
