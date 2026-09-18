package io.majo.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The MCP client runtime ({@code ctx.mcp}, roadmap-0.4 Phase 2): the
 * registry of connected stdio servers. Bridged tools call through here, so
 * the connection lifetime is owned by the plugin fiber (unmount closes the
 * processes).
 */
public final class McpService extends Service {

    /** ctx service key under which this service is registered. */
    public static final String NAME = "mcp";

    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();

    public McpService(Context ctx) {
        super(ctx, NAME);
    }

    void register(String serverName, McpConnection connection) {
        connections.put(serverName, connection);
    }

    /** Connected server names. */
    public List<String> servers() {
        return List.copyOf(connections.keySet());
    }

    /** Whether {@code serverName} is connected. */
    public boolean has(String serverName) {
        return connections.containsKey(serverName);
    }

    /** Tools exposed by a connected server. */
    public List<McpConnection.ToolInfo> tools(String serverName) {
        McpConnection connection = connections.get(serverName);
        if (connection == null) {
            throw new McpException("unknown MCP server \"" + serverName + "\"");
        }
        return connection.listTools();
    }

    /** Calls an MCP tool and returns the joined text of its content blocks. */
    public String call(String serverName, String toolName, JsonNode arguments) {
        McpConnection connection = connections.get(serverName);
        if (connection == null) {
            throw new McpException("unknown MCP server \"" + serverName + "\"");
        }
        return connection.callTool(toolName, arguments);
    }

    /** Generic JSON-RPC request against a connected server (resources/prompts). */
    public JsonNode request(String serverName, String method, JsonNode params) {
        McpConnection connection = connections.get(serverName);
        if (connection == null) {
            throw new McpException("unknown MCP server \"" + serverName + "\"");
        }
        return connection.request(method, params);
    }

    /** The server's usage instructions from the initialize handshake, or {@code null}. */
    public String instructions(String serverName) {
        McpConnection connection = connections.get(serverName);
        if (connection == null) {
            throw new McpException("unknown MCP server \"" + serverName + "\"");
        }
        return connection.instructions();
    }

    /** {@code resources/list} of one server. */
    public JsonNode listResources(String serverName) {
        return request(serverName, "resources/list", new ObjectMapper().createObjectNode());
    }

    /** {@code resources/templates/list} of one server. */
    public JsonNode listTemplates(String serverName) {
        return request(serverName, "resources/templates/list",
                new ObjectMapper().createObjectNode());
    }

    /** {@code resources/read} of one server. */
    public JsonNode readResource(String serverName, String uri) {
        return request(serverName, "resources/read",
                new ObjectMapper().createObjectNode().put("uri", uri));
    }

    void closeAll() {
        connections.values().forEach(McpConnection::close);
        connections.clear();
    }
}
