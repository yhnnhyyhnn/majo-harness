package io.majo.harness.webaccess;

import java.util.List;

/**
 * Static search backend (offline/demo): returns a fixed result list, keeping
 * the search seam usable before vendor backends (exa/perplexity/deepseek in
 * dsh) are mounted with their keys.
 */
public final class StaticSearchProvider implements SearchProvider {

    public static final String PROVIDER_NAME = "static";

    private final List<WebSearchResult> results;

    public StaticSearchProvider(List<WebSearchResult> results) {
        this.results = List.copyOf(results);
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public List<WebSearchResult> search(WebSearchRequest request) {
        String query = request.query().toLowerCase();
        return results.stream()
                .filter(result -> query.isEmpty()
                        || result.title().toLowerCase().contains(query)
                        || result.snippet().toLowerCase().contains(query)
                        || result.url().toLowerCase().contains(query))
                .limit(request.limit())
                .toList();
    }
}
