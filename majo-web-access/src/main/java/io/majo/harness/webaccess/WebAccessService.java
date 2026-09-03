package io.majo.harness.webaccess;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.jcordis.core.util.Disposable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The web access service ({@code ctx.web}, mirror of dsh's {@code ctx.web}):
 * one provider-neutral surface for search and fetch. Search and fetch backends
 * register independently; the service picks the first registered provider per
 * operation (or an explicit backend id), so model-facing tools stay stable
 * while backends come and go. The service itself makes no network calls — a
 * provider must be mounted before search or fetch can run, and a missing
 * provider fails with a structured message consumers can read.
 */
public final class WebAccessService extends Service {

    public static final String NAME = "web";

    private final Map<String, SearchProvider> searches = new ConcurrentHashMap<>();
    private final List<SearchProvider> searchOrder = new CopyOnWriteArrayList<>();
    private final Map<String, FetchProvider> fetches = new ConcurrentHashMap<>();
    private final List<FetchProvider> fetchOrder = new CopyOnWriteArrayList<>();

    public WebAccessService(Context ctx) {
        super(ctx, NAME);
    }

    /** Registers a search backend (appended); duplicates fail loudly. */
    public Disposable registerSearchProvider(SearchProvider provider) {
        SearchProvider previous = searches.putIfAbsent(provider.name(), provider);
        if (previous != null) {
            throw new IllegalStateException("search provider \"" + provider.name() + "\" has been registered");
        }
        searchOrder.add(provider);
        return () -> {
            searches.remove(provider.name(), provider);
            searchOrder.remove(provider);
        };
    }

    /** Registers a fetch backend (appended); duplicates fail loudly. */
    public Disposable registerFetchProvider(FetchProvider provider) {
        FetchProvider previous = fetches.putIfAbsent(provider.name(), provider);
        if (previous != null) {
            throw new IllegalStateException("fetch provider \"" + provider.name() + "\" has been registered");
        }
        fetchOrder.add(provider);
        return () -> {
            fetches.remove(provider.name(), provider);
            fetchOrder.remove(provider);
        };
    }

    /** Searches through the first usable backend (or an explicit id). */
    public List<WebSearchResult> search(WebSearchRequest request) {
        return search(request, null);
    }

    public List<WebSearchResult> search(WebSearchRequest request, String providerId) {
        SearchProvider provider = resolve(searchOrder, searches, providerId, "search");
        return provider.search(request);
    }

    /** Fetches a URL through the first usable backend (or an explicit id). */
    public WebFetchResult fetch(WebFetchRequest request) {
        return fetch(request, null);
    }

    public WebFetchResult fetch(WebFetchRequest request, String providerId) {
        FetchProvider provider = resolve(fetchOrder, fetches, providerId, "fetch");
        return provider.fetch(request);
    }

    private static <P> P resolve(List<P> order, Map<String, P> byName, String id, String verb) {
        if (id != null) {
            P exact = byName.get(id);
            if (exact == null) {
                throw new WebAccessException("web: unknown " + verb + " provider \"" + id
                        + "\"; mounted: " + byName.keySet());
            }
            return exact;
        }
        if (order.isEmpty()) {
            throw new WebAccessException("web: no " + verb + " provider is mounted; mount a backend first");
        }
        return order.get(0);
    }
}
