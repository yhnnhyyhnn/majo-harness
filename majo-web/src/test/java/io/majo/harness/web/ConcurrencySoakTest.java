package io.majo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency/resilience soak over the real HTTP surface (web-mock-like
 * profile without the approval gate so turns self-drive). System item #6:
 * turns from different sessions run in parallel (per-session locks), while
 * same-session bursts serialize safely; deletes and health checks must not
 * disturb turns in flight.
 */
final class ConcurrencySoakTest {

    @TempDir
    Path dir;

    private WebMain app;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.close();
            app = null;
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + app.port();
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
                - id: agent-loop
                  name: agent-loop
                  config:
                    systemPrompt: "You are a small calculator harness. Use the calc tool whenever asked for arithmetic."
                    maxSteps: 4
                - id: calc
                  name: calc
                """.formatted(sessions.toString().replace('\\', '/'),
                settings.toString().replace('\\', '/'));
        Path profile = dir.resolve("soak.yml");
        Files.writeString(profile, yml);
        app = new WebMain(0, profile.toString());
    }

    private String newSession(HttpClient client) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/sessions"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        return body.has("sessionId") ? body.get("sessionId").asText()
                : body.get("id").asText();
    }

    private String runTurn(HttpClient client, String sessionId, String task) throws Exception {
        URI uri = URI.create(baseUrl() + "/api/turn/stream?sessionId=" + sessionId
                + "&task=" + java.net.URLEncoder.encode(task, java.nio.charset.StandardCharsets.UTF_8));
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String doneAnswer = null;
        String event = null;
        for (String line : response.body().split("\\R")) {
            if (line.startsWith("event: ")) {
                event = line.substring("event: ".length());
            } else if (line.startsWith("data: ") && "done".equals(event)) {
                doneAnswer = MAPPER.readTree(line.substring("data: ".length()))
                        .get("answer").asText();
            }
        }
        assertThat(doneAnswer).as("session %s finished with a done frame", sessionId).isNotNull();
        return doneAnswer;
    }

    @Test
    void parallelCrossSessionTurnsStayIsolated() throws Exception {
        start();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
        int sessions = 24;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < sessions; i++) {
            ids.add(newSession(client));
        }
        List<String> sequentialIds = new ArrayList<>();
        for (int i = 0; i < sessions; i++) {
            sequentialIds.add(newSession(client));
        }

        // warm the JVM/paths before any measured run
        String warmSession = newSession(client);
        runTurn(client, warmSession, "2+2");
        runTurn(client, warmSession, "2+3");

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < sessions; i++) {
                int index = i;
                futures.add(pool.submit(() -> runTurn(client, ids.get(index), "3+" + index)));
            }
            long start = System.nanoTime();
            List<String> answers = new ArrayList<>();
            for (Future<String> future : futures) {
                answers.add(future.get(90, TimeUnit.SECONDS));
            }
            long parallelNanos = System.nanoTime() - start;

            for (int i = 0; i < sessions; i++) {
                assertThat(answers.get(i))
                        .as("session %d answered its own expression", i)
                        .isEqualTo("calculated: " + (3 + i));
            }

            // control group: same number of fresh sessions, run one by one
            long sequentialStart = System.nanoTime();
            for (int i = 0; i < sessions; i++) {
                runTurn(client, sequentialIds.get(i), "3+" + i);
            }
            long sequentialNanos = System.nanoTime() - sequentialStart;

            // hard concurrency evidence: parallel wall stays well under the
            // serialized total for the same workload
            assertThat(parallelNanos)
                    .as("parallel (per-session locks) faster than serialized")
                    .isLessThan((long) (sequentialNanos * 0.7));
        }
    }

    @Test
    void sameSessionBurstSerializesSafelyWhileOtherSessionsDelete() throws Exception {
        start();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
        String same = newSession(client);
        String victim = newSession(client);
        int burst = 10;

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicInteger healthy = new AtomicInteger();
            Future<?> storm = pool.submit(() -> {
                for (int i = 0; i < 20; i++) {
                    try {
                        HttpResponse<String> health = client.send(
                                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/health")).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                        if (health.statusCode() == 200) {
                            healthy.incrementAndGet();
                        }
                        // delete the *other* session mid-burst; same-session turns untouched
                        client.send(HttpRequest.newBuilder(
                                        URI.create(baseUrl() + "/api/sessions/" + victim))
                                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
                    } catch (Exception ignored) {
                        // keep hammering until the burst finishes
                    }
                }
            });
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < burst; i++) {
                futures.add(pool.submit(() -> runTurn(client, same, "4+4")));
            }
            for (Future<String> future : futures) {
                assertThat(future.get(90, TimeUnit.SECONDS)).isEqualTo("calculated: 8");
            }
            storm.get(60, TimeUnit.SECONDS);
            assertThat(healthy.get()).isGreaterThan(0);
        }
    }

    @Test
    void manyTurnHttpClientsOverlapWithoutErrors() throws Exception {
        start();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
        int clients = 8;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            ids.add(newSession(client));
        }
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Map.Entry<String, String>>> futures = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                String id = ids.get(i);
                futures.add(pool.submit(() -> Map.entry(id, runTurn(client, id, "7+7"))));
            }
            for (Future<Map.Entry<String, String>> future : futures) {
                Map.Entry<String, String> entry = future.get(90, TimeUnit.SECONDS);
                assertThat(entry.getValue()).isEqualTo("calculated: 14");
            }
        }
    }
}
