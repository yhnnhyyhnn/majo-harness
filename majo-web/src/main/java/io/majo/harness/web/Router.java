package io.majo.harness.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Minimal first-match route table replacing WebMain's hand-written if-else
 * chain. Pattern language: an exact path matches verbatim; a trailing
 * {@code *} matches any path with the given prefix and captures the rest;
 * a middle star captures the segment between prefix and suffix (as in
 * "/api/sessions/STAR/title" where STAR stands for the wildcard).
 * Registration order decides — same as the old chain, most specific routes
 * first.
 */
final class Router {

    /** Captured segment is "" for exact matches, the tail for prefix matches. */
    interface Handler {
        void handle(HttpExchange exchange, String captured) throws IOException;
    }

    private record Route(Set<String> methods, String pattern, Handler handler) {
    }

    private final List<Route> routes = new ArrayList<>();

    Router get(String pattern, Handler handler) {
        return on("GET", pattern, handler);
    }

    Router post(String pattern, Handler handler) {
        return on("POST", pattern, handler);
    }

    Router put(String pattern, Handler handler) {
        return on("PUT", pattern, handler);
    }

    Router delete(String pattern, Handler handler) {
        return on("DELETE", pattern, handler);
    }

    Router on(String methods, String pattern, Handler handler) {
        routes.add(new Route(Set.of(methods.split("\\|")), pattern, handler));
        return this;
    }

    /** Runs the first matching route; falls back to 404 JSON. */
    void dispatch(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        for (Route route : routes) {
            if (!route.methods().contains(method)) {
                continue;
            }
            String captured = match(route.pattern(), path);
            if (captured != null) {
                route.handler().handle(exchange, captured);
                return;
            }
        }
        Http.json(exchange, 404, java.util.Map.of("error", "not found: " + path));
    }

    /** {@code null} = no match; {@code ""} = exact match. */
    private static String match(String pattern, String path) {
        int star = pattern.indexOf('*');
        if (star < 0) {
            return pattern.equals(path) ? "" : null;
        }
        String prefix = pattern.substring(0, star);
        String suffix = pattern.substring(star + 1);
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return null;
        }
        if (suffix.isEmpty()) {
            return path.substring(prefix.length());
        }
        // segment form: keep the (possibly empty) middle so degenerate paths
        // still reach the handler and fail with its validation message
        return path.substring(prefix.length(), path.length() - suffix.length());
    }
}
