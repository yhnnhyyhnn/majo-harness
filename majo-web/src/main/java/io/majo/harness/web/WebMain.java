package io.majo.harness.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jcordis.core.context.Context;
import io.jcordis.core.logger.ConsoleExporter;
import io.majo.harness.agent.loop.AgentLoopService;
import io.majo.harness.boot.HarnessBoot;
import io.majo.harness.headless.CalculatorToolPlugin;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import io.majo.harness.title.SessionTitleService;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The majo web app: serves a static chat UI and a JSON turn API over one
 * booted harness instance (the {@code web} profile). Layout mirrors the dsh
 * web client's core shape — a session sidebar, a conversation of user/tool/
 * assistant bubbles, and a composer — while the server stays dependency-free
 * (JDK HttpServer + Jackson; the UI is vanilla JS, no build step).
 *
 * <p>Usage: {@code java -jar majo-web/target/majo-web-0.1.0-SNAPSHOT.jar [--port 8787]}.
 * Turns are serialized per instance (local single-user).
 */
public final class WebMain {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BUILTIN_PROFILE = "web.yml";

    private final int port;
    private final HarnessBoot boot;
    private final HttpServer server;
    private final ReentrantLock turnLock = new ReentrantLock();

    public WebMain(int port, String profile) throws IOException {
        this.port = port;
        Context root = Context.create();
        new ConsoleExporter(root);
        boot = new HarnessBoot(root)
                .register(CalculatorToolPlugin.NAME, new CalculatorToolPlugin());
        String profileText;
        String hint = profile;
        if ("web".equals(profile)) {
            hint = BUILTIN_PROFILE;
            try (InputStream stream = WebMain.class.getClassLoader().getResourceAsStream(BUILTIN_PROFILE)) {
                if (stream == null) {
                    throw new IOException("built-in profile missing: " + BUILTIN_PROFILE);
                }
                profileText = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } else {
            profileText = java.nio.file.Files.readString(java.nio.file.Path.of(profile));
        }
        boot.launch(boot.readProfileText(profileText, hint));

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (BindException e) {
            System.err.println("majo-web: port " + port + " is already in use (another instance running?);");
            System.err.println("  pick a free port, e.g. java -jar majo-web-0.1.0-SNAPSHOT.jar --port 9000");
            boot.dispose();
            throw e;
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::route);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void close() {
        server.stop(0);
        boot.dispose();
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if ("GET".equals(exchange.getRequestMethod()) && "/api/sessions".equals(path)) {
                json(exchange, 200, sessionsIndex());
            } else if ("GET".equals(exchange.getRequestMethod())
                    && path.startsWith("/api/sessions/")) {
                String sessionId = path.substring("/api/sessions/".length());
                json(exchange, 200, sessionDetail(sessionId));
            } else if ("POST".equals(exchange.getRequestMethod()) && "/api/turn".equals(path)) {
                json(exchange, 200, turn(exchange));
            } else if ("GET".equals(exchange.getRequestMethod())) {
                staticAsset(exchange, path);
            } else {
                json(exchange, 404, Map.of("error", "not found: " + path));
            }
        } catch (IllegalArgumentException e) {
            json(exchange, 400, Map.of("error", e.getMessage()));
        } catch (Throwable failure) {
            failure.printStackTrace();
            json(exchange, 500, Map.of("error", String.valueOf(failure.getMessage())));
        }
    }

    // ----- API -----

    private Map<String, Object> sessionsIndex() {
        SessionService sessions = boot.service(SessionService.NAME);
        SessionTitleService titles = boot.service(SessionTitleService.NAME);
        List<Map<String, Object>> list = new ArrayList<>();
        for (String sessionId : sessions.sessionIds()) {
            list.add(Map.of(
                    "id", sessionId,
                    "title", titles.title(sessionId),
                    "eventCount", sessions.events(sessionId).size()));
        }
        return Map.of("sessions", list);
    }

    private Map<String, Object> sessionDetail(String sessionId) {
        SessionService sessions = boot.service(SessionService.NAME);
        SessionTitleService titles = boot.service(SessionTitleService.NAME);
        return Map.of(
                "id", sessionId,
                "title", titles.title(sessionId),
                "events", eventsJson(sessions.events(sessionId)));
    }

    private Map<String, Object> turn(HttpExchange exchange) throws IOException {
        Map<?, ?> request = JSON.readValue(exchange.getRequestBody(), Map.class);
        Object taskValue = request.get("task");
        if (taskValue == null || String.valueOf(taskValue).isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        String task = String.valueOf(taskValue);
        SessionService sessions = boot.service(SessionService.NAME);
        AgentLoopService loop = boot.service(AgentLoopService.NAME);
        turnLock.lock();
        try {
            String sessionId = request.get("sessionId") == null
                    ? sessions.createSession()
                    : String.valueOf(request.get("sessionId"));
            String answer = loop.runTurn(sessionId, task);
            return Map.of(
                    "sessionId", sessionId,
                    "answer", answer,
                    "events", eventsJson(sessions.events(sessionId)));
        } finally {
            turnLock.unlock();
        }
    }

    private static List<Map<String, Object>> eventsJson(List<SessionEvent> events) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SessionEvent event : events) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("seq", event.seq());
            node.put("kind", event.type().name());
            if (event.content() != null) {
                node.put("content", event.content());
            }
            if (event.type() == SessionEventType.REQUEST_HEADER) {
                node.put("model", event.fields().get(SessionEvent.FIELD_MODEL));
                node.put("toolNames", event.fields().get(SessionEvent.FIELD_TOOL_NAMES));
            }
            if (event.type() == SessionEventType.ASSISTANT_MESSAGE
                    && event.fields().containsKey(SessionEvent.FIELD_TOOL_CALLS)) {
                node.put("toolCalls", event.fields().get(SessionEvent.FIELD_TOOL_CALLS));
            }
            if (event.type() == SessionEventType.TOOL_RESULT) {
                node.put("toolName", event.fields().get(SessionEvent.FIELD_TOOL_NAME));
                node.put("ok", event.fields().get(SessionEvent.FIELD_OK));
            }
            result.add(node);
        }
        return result;
    }

    // ----- static & plumbing -----

    private static void staticAsset(HttpExchange exchange, String path) throws IOException {
        if ("/".equals(path)) {
            path = "/index.html";
        }
        String resource = "static" + path;
        try (InputStream stream = WebMain.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                json(exchange, 404, Map.of("error", "not found: " + path));
                return;
            }
            byte[] payload = stream.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType(path));
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] payload = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
    }

    public static void main(String[] args) throws IOException {
        int port = 8787;
        String profile = "web";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--profile" -> profile = args[++i];
                default -> {
                    System.err.println("usage: majo-web [--port <n>] [--profile web|<file.yml>]");
                    System.exit(2);
                }
            }
        }
        WebMain app = new WebMain(port, profile);
        System.out.println("majo web: http://localhost:" + app.port());
        System.out.println("press Ctrl+C to stop");
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
    }
}
