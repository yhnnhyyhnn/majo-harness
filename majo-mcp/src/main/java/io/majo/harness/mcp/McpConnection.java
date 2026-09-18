package io.majo.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One MCP server connection — the initialize handshake is done, tools are
 * callable. The {@link #open} factory dispatches on the profile row's shape:
 * {@code command} (+ {@code args}, {@code env}) spawns a stdio server
 * process; {@code url} (+ {@code headers}) speaks Streamable HTTP.
 */
interface McpConnection extends AutoCloseable {

    /** One tool exposed by the server. */
    record ToolInfo(String name, String description, JsonNode inputSchema) {
    }

    /** Tools exposed by the server. */
    List<ToolInfo> listTools();

    /**
     * {@code tools/call}: returns the joined text of the response's content
     * blocks; {@code isError} results raise {@link McpException} carrying the
     * server's text.
     */
    String callTool(String toolName, JsonNode arguments);

    /** The server's declared capabilities from the initialize result. */
    JsonNode capabilities();

    /** Generic JSON-RPC request (resources/prompts lifecycle). */
    JsonNode request(String method, JsonNode params);

    /** Whether the transport is still usable. */
    boolean isAlive();

    @Override
    void close();

    /** Opens one fresh underlying connection (for reconnect wrappers). */
    interface Factory {
        McpConnection open() throws IOException;
    }

    /**
     * Opens a connection from one profile row: {@code url} → Streamable HTTP
     * (headers by env-name), {@code command} → stdio process (env by
     * env-name). Exactly one of the two forms is required.
     */
    static McpConnection open(String serverName, Map<?, ?> row, long timeoutMillis)
            throws IOException {
        boolean hasUrl = row.get("url") != null;
        boolean hasCommand = row.get("command") != null;
        if (hasUrl == hasCommand) {
            throw new IllegalArgumentException("mcp: server \"" + serverName
                    + "\" requires exactly one of \"command\" (stdio) or \"url\" (HTTP)");
        }
        if (hasUrl) {
            return McpHttpConnection.open(serverName, String.valueOf(row.get("url")),
                    resolveByName(row.get("headers"), serverName, "headers"), timeoutMillis);
        }
        List<String> args = new ArrayList<>();
        if (row.get("args") instanceof List<?> list) {
            for (Object item : list) {
                args.add(String.valueOf(item));
            }
        }
        return McpStdioConnection.connect(serverName, String.valueOf(row.get("command")),
                args, resolveByName(row.get("env"), serverName, "env"), timeoutMillis);
    }

    /**
     * Map entries reference environment variables by name — {@code ${VAR}}
     * expands at mount time and an unset variable fails loudly (credentials
     * by name, never by value); plain values pass through for non-secrets.
     */
    static Map<String, String> resolveByName(Object value, String serverName, String what) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> map)) {
            return resolved;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String raw = String.valueOf(entry.getValue());
            String expanded = raw.startsWith("${") && raw.endsWith("}")
                    ? System.getenv(raw.substring(2, raw.length() - 1))
                    : raw;
            if (expanded == null) {
                throw new IllegalArgumentException("mcp: server \"" + serverName + "\" "
                        + what + " \"" + entry.getKey() + "\" references variable " + raw
                        + " which is not set");
            }
            resolved.put(String.valueOf(entry.getKey()), expanded);
        }
        return resolved;
    }
}
