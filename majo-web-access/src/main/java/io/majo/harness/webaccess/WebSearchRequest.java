package io.majo.harness.webaccess;

/**
 * One search request: a query and an optional result cap.
 */
public record WebSearchRequest(String query, int limit) {

    public WebSearchRequest {
        query = query == null ? "" : query;
        if (limit < 1) {
            limit = 5;
        }
    }

    public static WebSearchRequest of(String query) {
        return new WebSearchRequest(query, 5);
    }
}
