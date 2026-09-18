package io.majo.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process Streamable HTTP MCP fixture: one endpoint serving initialize
 * (with a session id), tools/list + tools/call (an upper-casing "upper"
 * tool), resources and prompts. Responses are single JSON bodies by default;
 * flipping {@link #sseResponses} exercises the SSE parse path.
 */
final class HttpMcpFixture implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String SESSION_ID = "fixture-session-1";

    private final HttpServer server;
    /** When set, tools/call responses stream as text/event-stream. */
    public final AtomicBoolean sseResponses = new AtomicBoolean(false);
    public final AtomicReference<String> lastAuth = new AtomicReference<>();
    public final AtomicReference<String> lastSessionId = new AtomicReference<>();
    public final AtomicBoolean sawSessionIdOnCall = new AtomicBoolean(false);

    HttpMcpFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    private void handle(HttpExchange exchange) throws IOException {
        JsonNode message = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null) {
            lastAuth.set(auth);
        }
        String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (session != null) {
            lastSessionId.set(session);
        }
        String method = message.path("method").asText("");
        boolean notification = !message.hasNonNull("id");
        if (notification) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        if ("tools/call".equals(method)) {
            sawSessionIdOnCall.set(SESSION_ID.equals(session));
        }
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", message.get("id"));
        response.set("result", resultFor(method, message.path("params")));
        boolean asSse = sseResponses.get() && "tools/call".equals(method);
        byte[] payload;
        if (asSse) {
            payload = ("event: message\ndata: " + MAPPER.writeValueAsString(response) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().add("Mcp-Session-Id", SESSION_ID);
            exchange.sendResponseHeaders(200, payload.length);
        } else {
            payload = MAPPER.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().add("Mcp-Session-Id", SESSION_ID);
            }
            exchange.sendResponseHeaders(200, payload.length);
        }
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static ObjectNode resultFor(String method, JsonNode params) {
        ObjectNode result = MAPPER.createObjectNode();
        switch (method) {
            case "initialize" -> {
                result.put("protocolVersion", "2025-06-18");
                ObjectNode capabilities = result.putObject("capabilities");
                capabilities.set("tools", MAPPER.createObjectNode());
                capabilities.set("resources", MAPPER.createObjectNode());
                capabilities.set("prompts", MAPPER.createObjectNode());
                ObjectNode info = result.putObject("serverInfo");
                info.put("name", "fixture");
                info.put("version", "0.0.1");
            }
            case "tools/list" -> {
                ObjectNode tool = result.putArray("tools").addObject();
                tool.put("name", "upper");
                tool.put("description", "upper-cases the text");
                ObjectNode schema = tool.putObject("inputSchema");
                schema.put("type", "object");
                schema.putObject("properties").putObject("text").put("type", "string");
                schema.putArray("required").add("text");
            }
            case "tools/call" -> {
                String text = params.path("arguments").path("text").asText("");
                ObjectNode block = result.putArray("content").addObject();
                block.put("type", "text");
                block.put("text", text.toUpperCase());
            }
            case "resources/list" -> {
                ObjectNode resource = result.putArray("resources").addObject();
                resource.put("uri", "file:///probe.txt");
                resource.put("name", "probe");
                resource.put("description", "the probe resource");
            }
            case "resources/read" -> {
                ObjectNode content = result.putArray("contents").addObject();
                content.put("uri", params.path("uri").asText());
                content.put("mimeType", "text/plain");
                content.put("text", "resource-body");
            }
            case "prompts/list" -> {
                ObjectNode prompt = result.putArray("prompts").addObject();
                prompt.put("name", "greet");
                prompt.put("description", "a greeting template");
            }
            case "prompts/get" -> {
                ObjectNode promptMessage = result.putArray("messages").addObject();
                promptMessage.put("role", "user");
                ObjectNode content = promptMessage.putObject("content");
                content.put("type", "text");
                content.put("text", "greet " + params.path("arguments").path("who").asText(""));
            }
            default -> {
                // the fixture only serves the MCP methods the tests drive
                result.putArray("tools");
            }
        }
        return result;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
