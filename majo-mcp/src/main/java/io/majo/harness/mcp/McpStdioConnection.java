package io.majo.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * stdio MCP transport: spawns the server process, runs the JSON-RPC 2.0
 * handshake ({@code initialize} + initialized notification), and answers
 * {@code tools/list} / {@code tools/call}. Newline-delimited JSON on the
 * process's stdin/stdout; stderr is inherited for diagnostics. Responses are
 * matched by id on a reader thread; every request is bounded by the
 * configured timeout.
 */
final class McpStdioConnection implements McpConnection {

    /** The MCP protocol revision this transport speaks. */
    static final String PROTOCOL_VERSION = "2024-11-05";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final Process process;
    private final long timeoutMillis;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();
    private volatile JsonNode capabilities = MAPPER.createObjectNode();
    private volatile boolean closed;

    private McpStdioConnection(String serverName, Process process, long timeoutMillis) {
        this.serverName = serverName;
        this.process = process;
        this.timeoutMillis = timeoutMillis;
        Thread.ofVirtual().name("mcp-" + serverName + "-reader").start(this::readLoop);
    }

    /** Spawns the server process and completes the initialize handshake. */
    static McpConnection connect(String serverName, String command, List<String> args,
            Map<String, String> env, long timeoutMillis) throws IOException {
        List<String> argv = new ArrayList<>();
        argv.add(command);
        argv.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(argv);
        // dsh scrubbedParentEnv analog: the ambient environment is scrubbed to
        // an allowlist before the explicit config merges — credentials that
        // live in the parent env must not leak to server processes
        builder.environment().clear();
        builder.environment().putAll(scrub(System.getenv()));
        builder.environment().putAll(env);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        McpStdioConnection connection = new McpStdioConnection(serverName, process, timeoutMillis);
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
            connection.sendNotification("notifications/initialized");
        } catch (RuntimeException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /** Ambient keys a spawned server may see; everything else is scrubbed. */
    private static final List<String> ENV_ALLOWLIST = List.of(
            "PATH", "HOME", "USER", "USERNAME", "LANG", "LC_ALL",
            "TMPDIR", "TEMP", "TMP",
            // Windows process basics
            "SystemRoot", "SystemDrive", "COMSPEC", "PATHEXT",
            "HOMEDRIVE", "HOMEPATH", "APPDATA", "LOCALAPPDATA",
            "PROGRAMFILES", "PROGRAMW6432");

    /**
     * Filters an environment down to the allowlist — the dsh
     * {@code scrubbedParentEnv} analog.
     */
    static Map<String, String> scrub(Map<String, String> ambient) {
        Map<String, String> scrubbed = new java.util.LinkedHashMap<>();
        for (String key : ENV_ALLOWLIST) {
            String value = ambient.get(key);
            if (value != null) {
                scrubbed.put(key, value);
            }
        }
        return scrubbed;
    }

    @Override
    public List<ToolInfo> listTools() {
        JsonNode result = request("tools/list", MAPPER.createObjectNode());
        return toToolInfos(result);
    }

    /** Shared tools/list result mapping (stdio and HTTP transports). */
    static List<ToolInfo> toToolInfos(JsonNode result) {
        List<ToolInfo> tools = new ArrayList<>();
        for (JsonNode tool : result.path("tools")) {
            tools.add(new ToolInfo(
                    tool.path("name").asText(),
                    tool.hasNonNull("description") ? tool.get("description").asText() : null,
                    tool.path("inputSchema")));
        }
        return List.copyOf(tools);
    }

    @Override
    public String callTool(String toolName, JsonNode arguments) {
        JsonNode result = request("tools/call", MAPPER.createObjectNode()
                .put("name", toolName)
                .set("arguments", arguments == null ? MAPPER.createObjectNode() : arguments));
        return toCallText(toolName, result);
    }

    /** Shared tools/call result mapping (stdio and HTTP transports). */
    static String toCallText(String toolName, JsonNode result) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            if ("text".equals(block.path("type").asText()) && block.hasNonNull("text")) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(block.get("text").asText());
            }
        }
        if (result.path("isError").asBoolean(false)) {
            throw new McpException("MCP tool \"" + toolName + "\" failed: " + text);
        }
        return text.toString();
    }

    @Override
    public JsonNode capabilities() {
        return capabilities;
    }

    @Override
    public void close() {
        closed = true;
        process.destroy();
        for (CompletableFuture<JsonNode> future : pending.values()) {
            future.completeExceptionally(new McpException(
                    "connection to \"" + serverName + "\" closed"));
        }
        pending.clear();
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                handleMessage(line.trim());
            }
        } catch (IOException | UncheckedIOException e) {
            if (!closed) {
                failAllPending(new McpException(
                        "connection to \"" + serverName + "\" died: " + e.getMessage(), e));
            }
        } finally {
            failAllPending(new McpException(
                    "connection to \"" + serverName + "\" closed before answering"));
        }
    }

    private void handleMessage(String line) {
        JsonNode message;
        try {
            message = MAPPER.readTree(line);
        } catch (IOException e) {
            failAllPending(new McpException(
                    "malformed JSON from \"" + serverName + "\": " + line, e));
            return;
        }
        if (message.has("method")) {
            // server→client: answer requests (spec ping included) so the
            // server never stalls on us; notifications are dropped
            if (message.hasNonNull("id")) {
                String method = message.path("method").asText();
                ObjectNode response = MAPPER.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.set("id", message.get("id"));
                if ("ping".equals(method)) {
                    response.set("result", MAPPER.createObjectNode());
                } else {
                    ObjectNode error = response.putObject("error");
                    error.put("code", -32601);
                    error.put("message", "method not supported: " + method);
                }
                writeMessage(response);
            }
            return;
        }
        JsonNode idNode = message.get("id");
        if (idNode == null || !idNode.canConvertToLong()) {
            return;
        }
        CompletableFuture<JsonNode> future = pending.remove(idNode.asLong());
        if (future == null) {
            return;
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            future.completeExceptionally(new McpException("MCP error " + error.path("code").asInt()
                    + " from \"" + serverName + "\": " + error.path("message").asText()));
            return;
        }
        future.complete(message.get("result"));
    }

    @Override
    public JsonNode request(String method, JsonNode params) {
        long id = nextId.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            ObjectNode message = MAPPER.createObjectNode();
            message.put("jsonrpc", "2.0");
            message.put("id", id);
            message.put("method", method);
            if (params != null && params.size() > 0) {
                message.set("params", params);
            }
            writeMessage(message);
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new McpException("MCP request \"" + method + "\" on \"" + serverName
                    + "\" timed out after " + timeoutMillis + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP request \"" + method + "\" interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            pending.remove(id);
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new McpException("MCP request \"" + method + "\" failed", e.getCause());
        }
    }

    /** Sends a notification (no id, no response expected). */
    private void sendNotification(String method) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        writeMessage(message);
    }

    private synchronized void writeMessage(ObjectNode message) {
        try {
            process.getOutputStream().write(
                    (MAPPER.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        } catch (IOException e) {
            throw new McpException("cannot write to \"" + serverName + "\": " + e.getMessage(), e);
        }
    }

    private void failAllPending(McpException failure) {
        for (CompletableFuture<JsonNode> future : pending.values()) {
            future.completeExceptionally(failure);
        }
    }
}
