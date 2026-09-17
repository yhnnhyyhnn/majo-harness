package io.majo.harness.web.handler;

import com.sun.net.httpserver.HttpExchange;
import io.majo.harness.web.Http;
import io.majo.harness.web.Metrics;
import java.io.IOException;
import java.util.Map;

/** Static UI assets bundled in the jar ({@code static/} resources). */
public final class AssetHandlers {

    public AssetHandlers() {
    }

    public void staticAsset(HttpExchange exchange, String path) throws IOException {
        if ("/".equals(path)) {
            path = "/index.html";
        }
        byte[] payload = Http.resource("static" + path);
        if (payload == null) {
            Http.json(exchange, 404, Map.of("error", "not found: " + path));
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", Http.contentType(path));
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        Metrics.record(200);
    }
}
