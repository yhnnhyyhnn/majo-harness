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
        startWith(false, java.util.List.of());
    }

    private void startWith(boolean gateCalc, java.util.List<String> pluginArgs) throws Exception {
        Path sessions = dir.resolve("sessions");
        Files.createDirectories(sessions);
        Path settings = dir.resolve("settings.json");
        String gateRows = gateCalc
                ? """
                - id: interactions
                  name: interactions
                - id: tool-approval
                  name: tool-approval
                  config:
                    tools: [calc]
                """
                : "";
        String pluginRow = "";
        if (!pluginArgs.isEmpty()) {
            String name = pluginArgs.get(0).substring(0, pluginArgs.get(0).indexOf('='));
            pluginRow = "- id: ext\n  name: " + name + "\n";
        }
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
                %s%s""".formatted(sessions.toString().replace('\\', '/'),
                settings.toString().replace('\\', '/'), gateRows, pluginRow);
        Path profile = dir.resolve("soak.yml");
        Files.writeString(profile, yml);
        app = new WebMain(0, profile.toString(), pluginArgs);
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

    /** Compiles a tiny external plugin jar that registers a tool by name. */
    private static Path echoJar(Path dir, String toolName) throws Exception {
        String source = """
                package external.demo;

                import io.jcordis.core.context.Context;
                import io.jcordis.core.registry.Plugin;
                import io.jcordis.core.util.Disposable;
                import io.majo.harness.tools.Tool;
                import io.majo.harness.tools.ToolCall;
                import io.majo.harness.tools.ToolResult;
                import io.majo.harness.tools.ToolRegistry;
                import io.majo.harness.tools.ToolSpec;
                import java.util.HashMap;
                import java.util.Map;

                public final class EchoToolPlugin implements Plugin {
                    @Override
                    public Object apply(Context ctx, Object config) {
                        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
                        return tools.register(new Tool() {
                            @Override
                            public ToolSpec spec() {
                                return ToolSpec.of("__TOOL__", "echoes from a jar");
                            }

                            @Override
                            public ToolResult execute(ToolCall call) {
                                return ToolResult.ok("echo:" + call.arguments());
                            }
                        });
                    }

                    @Override
                    public Map<String, Object> inject() {
                        Map<String, Object> inject = new HashMap<>();
                        inject.put(ToolRegistry.NAME, null);
                        return inject;
                    }
                }
                """.replace("__TOOL__", toolName);
        Path srcDir = dir.resolve("src");
        Path classes = dir.resolve("classes");
        Files.createDirectories(srcDir);
        Files.createDirectories(classes);
        Path sourceFile = srcDir.resolve("EchoToolPlugin.java");
        Files.writeString(sourceFile, source);
        int compiled = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(), sourceFile.toString());
        if (compiled != 0) {
            throw new IllegalStateException("cannot compile soak plugin (exit " + compiled + ")");
        }
        Path jar = dir.resolve(toolName + ".jar");
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(
                Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry(
                    "META-INF/services/io.jcordis.core.registry.Plugin"));
            out.write("external.demo.EchoToolPlugin\n".getBytes());
            out.closeEntry();
            Path pkg = classes.resolve("external/demo");
            try (var files = Files.list(pkg)) {
                for (Path classFile : files.filter(p -> p.getFileName().toString().endsWith(".class")).toList()) {
                    out.putNextEntry(new java.util.jar.JarEntry("external/demo/"
                            + classFile.getFileName()));
                    out.write(Files.readAllBytes(classFile));
                    out.closeEntry();
                }
            }
        }
        return jar;
    }

    private int healthTools() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        return MAPPER.readTree(health.body()).get("tools").asInt();
    }

    private long healthErrors() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        return MAPPER.readTree(health.body()).get("errors").asLong();
    }

    @Test
    void pluginHotReloadsDuringActiveTurnsStayConsistent() throws Exception {
        Path jar = echoJar(dir.resolve("echo"), "echo_soak");
        startWith(false, java.util.List.of("reload-echo=" + jar));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
        assertThat(healthTools()).isEqualTo(2); // calc + echo_soak

        int turns = 8;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            ids.add(newSession(client));
        }
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < turns; i++) {
                int index = i;
                futures.add(pool.submit(() -> runTurn(client, ids.get(index), "5+" + index)));
            }
            for (int round = 0; round < 6; round++) {
                HttpResponse<String> reload = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl() + "/api/plugins/reload-echo/reload"))
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .header("Content-Type", "application/json")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(reload.statusCode()).isEqualTo(200);
                Thread.sleep(80);
            }
            for (int i = 0; i < turns; i++) {
                Future<String> future = futures.get(i);
                assertThat(future.get(90, TimeUnit.SECONDS))
                        .as("turn %d unaffected by jar churn", i)
                        .isEqualTo("calculated: " + (5 + i));
            }
        }
        assertThat(healthTools()).isEqualTo(2); // still mounted after 6 reloads

        HttpResponse<String> unload = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/plugins/reload-echo"))
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unload.statusCode()).isEqualTo(200);
        assertThat(healthTools()).isEqualTo(1); // echo rolled back cleanly
    }

    private String approvalIdFrom(HttpResponse<java.io.InputStream> response) throws Exception {
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        String line;
        String event = null;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("event: ")) {
                event = line.substring("event: ".length());
            } else if (line.startsWith("data: ") && "approval".equals(event)) {
                return MAPPER.readTree(line.substring("data: ".length())).get("id").asText();
            }
        }
        return null;
    }

    @Test
    void disconnectedApprovalStreamTimesOutAndNeverWedgesOthers() throws Exception {
        System.setProperty("majo.approvalTimeoutSeconds", "3");
        try {
            startWith(true, java.util.List.of());
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10)).build();
            String abandoned = newSession(client);
            String healthy = newSession(client);
            long errorsBefore = healthErrors();

            // session "abandoned": open an approval-pending stream, then drop it
            URI stream = URI.create(baseUrl() + "/api/turn/stream?sessionId=" + abandoned
                    + "&task=" + java.net.URLEncoder.encode("3+3", java.nio.charset.StandardCharsets.UTF_8));
            HttpResponse<java.io.InputStream> opened = client.send(
                    HttpRequest.newBuilder(stream).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertThat(opened.statusCode()).isEqualTo(200);
            // wait until the approval actually lands server-side, then disconnect
            try (java.util.concurrent.ExecutorService readPool =
                    Executors.newVirtualThreadPerTaskExecutor()) {
                Future<String> approval = readPool.submit(() -> approvalIdFrom(opened));
                String id = approval.get(20, TimeUnit.SECONDS);
                assertThat(id).isNotNull();
                opened.body().close(); // drop the stream mid-approval
            }

            // orphan approval must time out (3s) without wedging anything
            Thread.sleep(5000);
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl() + "/api/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(MAPPER.readTree(health.body()).get("ok").asBoolean()).isTrue();
            // aborted SSE + the abandoned stream's fail-safe are not server errors
            assertThat(healthErrors()).as("client aborts never count as server errors")
                    .isEqualTo(errorsBefore);

            // a new gated turn on another session still gets its own approval
            // and completes once decided (per-stream routing intact)
            URI streamB = URI.create(baseUrl() + "/api/turn/stream?sessionId=" + healthy
                    + "&task=" + java.net.URLEncoder.encode("3+3", java.nio.charset.StandardCharsets.UTF_8));
            try (java.util.concurrent.ExecutorService readPool =
                    Executors.newVirtualThreadPerTaskExecutor()) {
                HttpResponse<java.io.InputStream> openedB = client.send(
                        HttpRequest.newBuilder(streamB).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                String id = readPool.submit(() -> approvalIdFrom(openedB)).get(20, TimeUnit.SECONDS);
                assertThat(id).isNotNull();
                HttpResponse<String> decided = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl() + "/api/approvals/" + id))
                                .POST(HttpRequest.BodyPublishers.ofString("{\"decision\":\"allow\"}"))
                                .header("Content-Type", "application/json")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(decided.statusCode()).isEqualTo(200);
                // stream now completes with the calc result
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(openedB.body(),
                                java.nio.charset.StandardCharsets.UTF_8));
                String line;
                String event = null;
                boolean done = false;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                        event = line.substring("event: ".length());
                    } else if (line.startsWith("data: ") && "done".equals(event)) {
                        done = MAPPER.readTree(line.substring("data: ".length()))
                                .get("answer").asText().contains("6");
                    }
                }
                assertThat(done).isTrue();
            }

            // abandoned session is deletable and the store replays fine
            HttpResponse<String> deleted = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl() + "/api/sessions/" + abandoned))
                            .DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(deleted.statusCode()).isEqualTo(200);
        } finally {
            System.clearProperty("majo.approvalTimeoutSeconds");
        }
    }

    @Test
    void bindsLoopbackByDefaultAndHonoursHostOverride() throws Exception {
        System.setProperty("majo.host", "127.0.0.1");
        try {
            assertThat(WebMain.defaultHost()).isEqualTo("127.0.0.1");
            startWith(false, java.util.List.of()); // default host from property
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
                            + "/api/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
        } finally {
            System.clearProperty("majo.host");
        }
    }

    @Test
    void pendingApprovalStreamKeepsHeartbeatAliveAndErrorsStayClean() throws Exception {
        startWith(true, java.util.List.of()); // approval timeout defaults to 30s
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
        String session = newSession(client);
        URI stream = URI.create(baseUrl() + "/api/turn/stream?sessionId=" + session
                + "&task=" + java.net.URLEncoder.encode("2+2", java.nio.charset.StandardCharsets.UTF_8));

        HttpResponse<java.io.InputStream> opened = client.send(
                HttpRequest.newBuilder(stream).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(opened.body(), java.nio.charset.StandardCharsets.UTF_8));
        String approvalId = null;
        boolean heartbeat = false;
        String line;
        String event = null;
        long deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline) {
            line = reader.readLine();
            if (line == null) {
                break;
            }
            if (line.startsWith("event: ")) {
                event = line.substring("event: ".length());
            } else if (line.startsWith("data: ") && "approval".equals(event)) {
                approvalId = MAPPER.readTree(line.substring("data: ".length())).get("id").asText();
            } else if (": hb".equals(line.trim()) && approvalId != null) {
                heartbeat = true;
                break;
            }
        }
        assertThat(approvalId).as("approval surfaced while blocked").isNotNull();
        assertThat(heartbeat).as("heartbeat frame arrives while approval pends").isTrue();

        HttpResponse<String> decided = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/approvals/" + approvalId))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"decision\":\"allow\"}"))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(decided.statusCode()).isEqualTo(200);
        boolean done = false;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("event: ")) {
                event = line.substring("event: ".length());
            } else if (line.startsWith("data: ") && "done".equals(event)) {
                done = true;
            }
        }
        assertThat(done).as("decided stream completes").isTrue();
        assertThat(healthErrors()).as("no server errors across the whole flow").isZero();
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
