package io.majo.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streamable HTTP MCP transport (roadmap-0.5): JSON-RPC over POST to the
 * server's endpoint, answering either a single JSON body or an
 * {@code text/event-stream} of messages; the {@code Mcp-Session-Id} response
 * header is captured at initialize and echoed on later requests, and
 * {@code DELETE} on close ends the session. Auth is whatever static headers
 * the profile row resolved (bearer/custom by env-name) — OAuth flows are
 * explicitly out of scope.
 */
final class McpHttpConnection implements McpConnection {

    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final URI endpoint;
    private final Map<String, String> headers;
    private final HttpClient client;
    private final long timeoutMillis;
    private final AtomicLong nextId = new AtomicLong();
    private volatile JsonNode capabilities = MAPPER.createObjectNode();
    private volatile String sessionId;
    private volatile String instructions;
    private volatile boolean closed;

    private McpHttpConnection(String serverName, URI endpoint, Map<String, String> headers,
            long timeoutMillis) {
        this.serverName = serverName;
        this.endpoint = endpoint;
        this.headers = headers;
        this.timeoutMillis = timeoutMillis;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMillis))
                .build();
    }

    /** Runs the initialize handshake against the remote endpoint. */
    static McpConnection open(String serverName, String url, Map<String, String> headers,
            long timeoutMillis) {
        URI endpoint;
        try {
            endpoint = URI.create(url);
            if (endpoint.getScheme() == null
                    || !(endpoint.getScheme().equals("http") || endpoint.getScheme().equals("https"))) {
                throw new IllegalArgumentException("not an http(s) URL");
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("mcp: server \"" + serverName
                    + "\" has an invalid url \"" + url + "\"", e);
        }
        McpHttpConnection connection = new McpHttpConnection(serverName, endpoint, headers,
                timeoutMillis);
        try {
            ObjectNode clientInfo = MAPPER.createObjectNode();
            clientInfo.put("name", "majo-harness");
            clientInfo.put("version", "1.0");
            ObjectNode params = MAPPER.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.set("capabilities", MAPPER.createObjectNode());
            params.set("clientInfo", clientInfo);
            JsonNode result = connection.request("initialize", params);
            if (result.path("protocolVersion").isMissingNode()) {
                throw new McpException("server \"" + serverName
                        + "\" did not answer the initialize handshake");
            }
            connection.capabilities = result.path("capabilities");
            if (result.hasNonNull("instructions")) {
                connection.instructions = result.get("instructions").asText();
            }
            connection.notify("notifications/initialized");
        } catch (RuntimeException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    @Override
    public List<ToolInfo> listTools() {
        return McpStdioConnection.toToolInfos(request("tools/list", MAPPER.createObjectNode()));
    }

    @Override
    public String callTool(String toolName, JsonNode arguments) {
        JsonNode result = request("tools/call", MAPPER.createObjectNode()
                .put("name", toolName)
                .set("arguments", arguments == null ? MAPPER.createObjectNode() : arguments));
        return McpStdioConnection.toCallText(toolName, result);
    }

    @Override
    public JsonNode capabilities() {
        return capabilities;
    }

    @Override
    public String instructions() {
        return instructions;
    }

    @Override
    public boolean isAlive() {
        return !closed;
    }

    @Override
    public void close() {
        closed = true;
        if (sessionId == null) {
            return;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .DELETE();
            applyContext(builder);
            client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // best-effort session teardown; the server reaps idle sessions
        }
    }

    @Override
    public JsonNode request(String method, JsonNode params) {
        long id = nextId.incrementAndGet();
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        if (params != null && params.size() > 0) {
            message.set("params", params);
        }
        JsonNode result = post(message);
        if (result == null) {
            throw new McpException("MCP request \"" + method + "\" on \"" + serverName
                    + "\" got no result");
        }
        return result;
    }

    /** A notification: POST, expect a 2xx, no response parsing. */
    private void notify(String method) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        post(message);
    }

    /**
     * POSTs one JSON-RPC message and returns the matching {@code result}
     * node — {@code null} when the server answered without a body (202 for
     * notifications). Accepts both wire shapes the Streamable HTTP spec
     * allows: a single JSON response or an SSE stream carrying it.
     */
    private JsonNode post(JsonNode message) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(message)));
            applyContext(builder);
            headers.forEach(builder::header);
            HttpResponse<InputStream> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                throw new McpException("MCP HTTP " + response.statusCode() + " from \""
                        + serverName + "\"");
            }
            response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> sessionId = id);
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            InputStream body = response.body();
            if (contentType.contains("text/event-stream")) {
                return readSseResponse(body, message.path("id").asLong(-1));
            }
            String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return null;
            }
            JsonNode answer = MAPPER.readTree(text);
            JsonNode error = answer.get("error");
            if (error != null && !error.isNull()) {
                throw new McpException("MCP error " + error.path("code").asInt() + " from \""
                        + serverName + "\": " + error.path("message").asText());
            }
            return answer.get("result");
        } catch (IOException e) {
            throw new McpException("MCP HTTP request to \"" + serverName + "\" failed: "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP HTTP request to \"" + serverName + "\" interrupted", e);
        }
    }

    /** Reads an SSE stream until the response with {@code expectedId} arrives. */
    private JsonNode readSseResponse(InputStream body, long expectedId) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).strip();
                if (payload.isEmpty()) {
                    continue;
                }
                JsonNode answer = MAPPER.readTree(payload);
                JsonNode id = answer.get("id");
                if (id == null || !id.canConvertToLong() || id.asLong() != expectedId) {
                    continue; // notifications and other traffic on the stream
                }
                JsonNode error = answer.get("error");
                if (error != null && !error.isNull()) {
                    throw new McpException("MCP error " + error.path("code").asInt() + " from \""
                            + serverName + "\": " + error.path("message").asText());
                }
                return answer.get("result");
            }
        }
        throw new McpException("MCP stream from \"" + serverName
                + "\" ended without a response for request id " + expectedId);
    }

    private void applyContext(HttpRequest.Builder builder) {
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
    }
}
