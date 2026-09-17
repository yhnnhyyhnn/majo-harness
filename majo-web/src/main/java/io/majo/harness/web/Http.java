package io.majo.harness.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Shared HTTP plumbing: JSON writer, small exchange helpers, token auth. */
public final class Http {

    public static final ObjectMapper JSON = new ObjectMapper();

    static {
        // optional wire fields (OptionalWire) stay absent instead of null
        JSON.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    }

    private Http() {
    }

    public static void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] payload = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        Metrics.record(status);
    }

    public static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }

    public static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js") || path.endsWith(".mjs")) {
            return "text/javascript; charset=utf-8";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (path.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (path.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (path.endsWith(".json")) {
            return "application/json";
        }
        return "application/octet-stream";
    }

    public static boolean isClientAbort(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof java.net.SocketException) {
                return true;
            }
            String message = String.valueOf(cursor.getMessage()).toLowerCase();
            if (message.contains("broken pipe") || message.contains("connection reset")
                    || message.contains("socket closed") || message.contains("stream closed")
                    || message.contains("aborted")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /**
     * Whether a request carries the expected token (Bearer header or ?token=).
     * Both sides are padded through a constant-time comparison so response
     * timing does not leak the secret. {@code ?token=} exists only because
     * EventSource cannot set headers; it is a local single-user affordance.
     */
    public static boolean authorized(HttpExchange exchange, String expected) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && constantTimeEquals(header, "Bearer " + expected)) {
            return true;
        }
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("token=") && constantTimeEquals(part.substring(6), expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    public static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public static String stringField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** Reads a classpath resource fully, or returns {@code null} when absent. */
    public static byte[] resource(String name) throws IOException {
        try (InputStream stream = Http.class.getClassLoader().getResourceAsStream(name)) {
            return stream == null ? null : stream.readAllBytes();
        }
    }
}
