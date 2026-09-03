package io.majo.harness.webaccess;

import java.util.List;

/**
 * A search backend (Strategy): searches the web behind the neutral
 * {@code ctx.web} service. Results are provider-owned text — consumers must
 * label them external/untrusted. Vendor backends (exa/perplexity/deepseek in
 * dsh) implement this interface; the shipped static backend keeps the seam
 * usable offline.
 */
public interface SearchProvider {

    /** A human-readable backend name for diagnostics and explicit selection. */
    String name();

    List<WebSearchResult> search(WebSearchRequest request);
}
