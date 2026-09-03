package io.majo.harness.webaccess;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers the static search backend. Config is a list of results:
 * <pre>
 * results:
 *   - url: https://example.org/faq
 *     title: Example FAQ
 *     snippet: Everything about example.org
 * </pre>
 * An absent/empty list yields a provider that matches nothing (still a mounted
 * backend, so calls produce empty results rather than "no provider").
 */
public final class StaticSearchPlugin implements Plugin {

    public static final String NAME = "web-search-static";

    @Override
    public Object apply(Context ctx, Object config) {
        WebAccessService web = ctx.get(WebAccessService.NAME);
        List<WebSearchResult> results = new ArrayList<>();
        if (config instanceof Map<?, ?> map && map.get("results") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> result)) {
                    throw new IllegalArgumentException("web-search-static: each result must be a map");
                }
                Object url = result.get("url");
                if (url == null) {
                    throw new IllegalArgumentException("web-search-static: result requires \"url\"");
                }
                results.add(new WebSearchResult(
                        String.valueOf(url),
                        result.get("title") == null ? "" : String.valueOf(result.get("title")),
                        result.get("snippet") == null ? "" : String.valueOf(result.get("snippet"))));
            }
        }
        Disposable registration = web.registerSearchProvider(new StaticSearchProvider(results));
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
