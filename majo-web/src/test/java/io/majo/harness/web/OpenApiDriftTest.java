package io.majo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI drift guard: every path/method documented in the shipped
 * {@code openapi.json} must still be routed by a live server. A documented
 * route that disappears shows up here as either an unknown-path 404 or a
 * method never matching — instead of silently rotting docs.
 */
final class OpenApiDriftTest {

    @TempDir
    Path dir;

    private WebMain app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.close();
            app = null;
        }
    }

    private void start() throws Exception {
        Path sessions = dir.resolve("sessions");
        Files.createDirectories(sessions);
        Path settings = dir.resolve("settings.json");
        String yml = """
                - id: session
                  name: session
                  config:
                    store: file
                    path: %s
                - id: session-projections
                  name: session-projections
                - id: tools
                  name: tools
                - id: settings
                  name: settings
                  config:
                    path: %s
                - id: llm
                  name: llm
                  config:
                    defaultModel: mock
                - id: llm-mock
                  name: llm-mock
                - id: credentials
                  name: credentials
                - id: session-title
                  name: session-title
                - id: session-title-heuristic
                  name: session-title-heuristic
                - id: agent-loop
                  name: agent-loop
                - id: calc
                  name: calc
                """.formatted(sessions.toString().replace('\\', '/'),
                settings.toString().replace('\\', '/'));
        Path profile = dir.resolve("openapi.yml");
        Files.writeString(profile, yml);
        app = new WebMain(0, profile.toString());
    }

    @Test
    @org.junit.jupiter.api.Timeout(60)
    void everyDocumentedRouteIsStillServed() throws Exception {
        start();
        try (InputStream spec = OpenApiDriftTest.class.getClassLoader()
                .getResourceAsStream("openapi.json")) {
            assertThat(spec).as("openapi.json packaged").isNotNull();
            JsonNode openApi = new ObjectMapper().readTree(spec);
            String base = "http://127.0.0.1:" + app.port();
            HttpClient client = HttpClient.newHttpClient();
            String sessionId = createSession(client, base);
            List<String> problems = new ArrayList<>();

            for (var paths = openApi.path("paths").fields(); paths.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = paths.next();
                String documented = entry.getKey();
                for (var methods = entry.getValue().fields(); methods.hasNext(); ) {
                    Map.Entry<String, JsonNode> methodEntry = methods.next();
                    String method = methodEntry.getKey().toUpperCase();
                    String rendered = documented
                            .replace("{id}", sessionId)
                            .replace("{seq}", "1")
                            .replace("{name}", "status");
                    String body = sampleBody(documented, method);
                    Probe probe;
                    try {
                        probe = fetch(client, base, method, rendered, body);
                    } catch (Exception e) {
                        problems.add(method + " " + documented + " -> request failed: " + e);
                        continue;
                    }
                    boolean unknownPath = probe.status() == 404
                            && probe.text().contains("\"not found: ");
                    boolean serverError = probe.status() == 500;
                    if (unknownPath || serverError) {
                        problems.add(method + " " + documented + " -> HTTP " + probe.status()
                                + (unknownPath ? " (unknown-path 404)" : " (500)")
                                + " body=" + probe.text());
                    }
                }
            }
            assertThat(problems)
                    .as("documented routes that no longer exist / crash")
                    .isEmpty();
        }
    }

    private String createSession(HttpClient client, String base) throws Exception {
        HttpResponse<String> created = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/sessions"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode body = new ObjectMapper().readTree(created.body());
        return body.has("sessionId") ? body.get("sessionId").asText() : body.get("id").asText();
    }

    /** A tiny body per documented route; unknown/malformed bodies may 400 (fine). */
    private static String sampleBody(String documented, String method) {
        if ("GET".equals(method) || "DELETE".equals(method)) {
            return "{}";
        }
        if (documented.endsWith("/model")) {
            return "{\"model\":\"mock\"}";
        }
        if (documented.equals("/api/turn")) {
            return "{\"task\":\"1+1\"}";
        }
        if (documented.equals("/api/commands/{name}")) {
            return "{\"task\":\"2+2\"}";
        }
        return "{}";
    }

    private record Probe(int status, String text) {
    }

    private static Probe fetch(HttpClient client, String base, String method, String rendered,
            String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + rendered));
        builder.timeout(java.time.Duration.ofSeconds(5));
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        return new Probe(response.statusCode(), response.body());
    }
}
