package io.majo.harness.webaccess;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import java.util.HashMap;
import java.util.Map;

/**
 * Registers the key-less DuckDuckGo search backend
 * ({@code web-search-duckduckgo}). Mounting is lazy — profile boot never
 * touches the network, only an actual search does.
 */
public final class DdgSearchPlugin implements Plugin {

    public static final String NAME = "web-search-duckduckgo";

    @Override
    public Object apply(Context ctx, Object config) {
        WebAccessService web = ctx.get(WebAccessService.NAME);
        Disposable registration = web.registerSearchProvider(new DdgSearchProvider());
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
