package io.majo.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import io.majo.harness.util.Disposables;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mounts the MCP client (roadmap-0.4/0.5, the dsh MCP analog): every
 * configured server row opens a connection — {@code command} spawns a stdio
 * process, {@code url} speaks Streamable HTTP — and its {@code tools/list}
 * result is bridged into the {@code ToolRegistry} as namespaced
 * {@code mcp__<server>__<tool>} tools with verbatim JSON schemas; they
 * appear in {@code /api/tools} and the generated tool catalog for free, and
 * every call rides the ordinary approval seam. Servers declaring the
 * resources/prompts capability get one namespaced read-only tool each.
 * Calls are bounded by the request timeout; a server that fails to mount is
 * logged loudly but does not break the boot or the other servers.
 *
 * <p>Config: {@code {requestTimeoutSeconds: <n>, servers: {<name>: {command,
 * args, env} | {url, headers}}}} — env/header values reference environment
 * variables by name (credentials never live in the profile); HTTP auth is
 * explicit headers only, no OAuth.
 */
public final class McpPlugin implements Plugin {

    public static final String NAME = "mcp";
    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 10;

    static final Logger LOG = LoggerFactory.getLogger(McpPlugin.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Object apply(Context ctx, Object config) {
        Map<?, ?> map = config instanceof Map<?, ?> m ? m : Map.of();
        long timeoutMillis = DEFAULT_REQUEST_TIMEOUT_SECONDS * 1000L;
        if (map.get("requestTimeoutSeconds") instanceof Number number && number.longValue() > 0) {
            timeoutMillis = number.longValue() * 1000L;
        }
        Map<?, ?> servers = map.get("servers") instanceof Map<?, ?> s ? s : Map.of();

        McpService service = new McpService(ctx);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        List<Disposable> registrations = new ArrayList<>();
        for (Map.Entry<?, ?> entry : servers.entrySet()) {
            String serverName = String.valueOf(entry.getKey());
            Map<?, ?> row = entry.getValue() instanceof Map<?, ?> r ? r : Map.of();
            try {
                McpConnection connection = McpConnection.open(serverName, row, timeoutMillis);
                service.register(serverName, connection);
                for (McpConnection.ToolInfo info : connection.listTools()) {
                    registrations.add(tools.register(bridgedTool(service, serverName, info)));
                }
                registrations.addAll(capabilityTools(service, tools, serverName, connection));
                LOG.info("mcp: mounted server \"{}\" with {} tool(s)", serverName,
                        service.tools(serverName).size());
            } catch (RuntimeException | IOException e) {
                LOG.error("mcp: server \"{}\" failed to mount: {}", serverName, e.getMessage(), e);
            }
        }
        registrations.add(new Disposable() {
            @Override
            public void dispose() {
                service.closeAll();
            }
        });
        return Disposables.composite(registrations);
    }

    /**
     * Capability-gated extras (roadmap-0.5): when the server declares the
     * resources/prompts capability, one namespaced read-only tool each
     * exposes them — the tool descriptions enumerate what the server offered
     * at mount time. Failures here are loud but non-fatal (tools stay).
     */
    private static List<Disposable> capabilityTools(McpService service, ToolRegistry tools,
            String serverName, McpConnection connection) {
        List<Disposable> registrations = new ArrayList<>();
        JsonNode capabilities = connection.capabilities();
        if (capabilities.path("resources").isObject()) {
            try {
                JsonNode result = connection.request("resources/list",
                        MAPPER.createObjectNode());
                StringBuilder description = new StringBuilder(
                        "Read an MCP resource from server \"" + serverName
                                + "\" by uri. Available at mount time:");
                int count = 0;
                for (JsonNode resource : result.path("resources")) {
                    description.append("\n- ").append(resource.path("uri").asText());
                    if (resource.hasNonNull("description")) {
                        description.append(" — ").append(resource.get("description").asText());
                    } else if (resource.hasNonNull("name")) {
                        description.append(" — ").append(resource.get("name").asText());
                    }
                    count++;
                }
                if (count > 0) {
                    ObjectNode schema = MAPPER.createObjectNode();
                    schema.put("type", "object");
                    schema.putObject("properties").putObject("uri").put("type", "string");
                    schema.putArray("required").add("uri");
                    registrations.add(tools.register(new Tool() {
                        @Override
                        public ToolSpec spec() {
                            return new ToolSpec("mcp__" + serverName + "__read_resource",
                                    description.toString(), schema);
                        }

                        @Override
                        public ToolResult execute(ToolCall call) {
                            try {
                                JsonNode args = call.arguments() == null || call.arguments().isBlank()
                                        ? MAPPER.createObjectNode()
                                        : MAPPER.readTree(call.arguments());
                                String uri = args.path("uri").asText("");
                                if (uri.isBlank()) {
                                    return ToolResult.error("read_resource: pass a resource uri");
                                }
                                return ToolResult.ok(service.request(serverName, "resources/read",
                                        MAPPER.createObjectNode().put("uri", uri))
                                        .path("contents").path(0).path("text").asText(""), Map.of());
                            } catch (McpException e) {
                                return ToolResult.error(e.getMessage());
                            } catch (IOException e) {
                                return ToolResult.error("mcp: cannot parse arguments: "
                                        + e.getMessage());
                            }
                        }
                    }));
                }
            } catch (RuntimeException e) {
                LOG.error("mcp: resources of server \"{}\" failed to mount: {}",
                        serverName, e.getMessage(), e);
            }
        }
        if (capabilities.path("prompts").isObject()) {
            try {
                JsonNode result = connection.request("prompts/list", MAPPER.createObjectNode());
                StringBuilder description = new StringBuilder(
                        "Fetch an MCP prompt template from server \"" + serverName
                                + "\" by name. Available at mount time:");
                int count = 0;
                for (JsonNode prompt : result.path("prompts")) {
                    description.append("\n- ").append(prompt.path("name").asText());
                    if (prompt.hasNonNull("description")) {
                        description.append(" — ").append(prompt.get("description").asText());
                    }
                    count++;
                }
                if (count > 0) {
                    ObjectNode schema = MAPPER.createObjectNode();
                    schema.put("type", "object");
                    ObjectNode properties = schema.putObject("properties");
                    properties.putObject("name").put("type", "string");
                    properties.putObject("arguments").put("type", "object");
                    schema.putArray("required").add("name");
                    registrations.add(tools.register(new Tool() {
                        @Override
                        public ToolSpec spec() {
                            return new ToolSpec("mcp__" + serverName + "__get_prompt",
                                    description.toString(), schema);
                        }

                        @Override
                        public ToolResult execute(ToolCall call) {
                            try {
                                JsonNode args = call.arguments() == null || call.arguments().isBlank()
                                        ? MAPPER.createObjectNode()
                                        : MAPPER.readTree(call.arguments());
                                String name = args.path("name").asText("");
                                if (name.isBlank()) {
                                    return ToolResult.error("get_prompt: pass a prompt name");
                                }
                                ObjectNode params = MAPPER.createObjectNode().put("name", name);
                                if (args.path("arguments").isObject()) {
                                    params.set("arguments", args.get("arguments"));
                                }
                                JsonNode result = service.request(serverName, "prompts/get", params);
                                StringBuilder text = new StringBuilder();
                                for (JsonNode message : result.path("messages")) {
                                    if (text.length() > 0) {
                                        text.append('\n');
                                    }
                                    text.append(message.path("role").asText("user")).append(": ")
                                            .append(message.path("content").path("text").asText());
                                }
                                return ToolResult.ok(text.toString(), Map.of());
                            } catch (McpException e) {
                                return ToolResult.error(e.getMessage());
                            } catch (IOException e) {
                                return ToolResult.error("mcp: cannot parse arguments: "
                                        + e.getMessage());
                            }
                        }
                    }));
                }
            } catch (RuntimeException e) {
                LOG.error("mcp: prompts of server \"{}\" failed to mount: {}",
                        serverName, e.getMessage(), e);
            }
        }
        return registrations;
    }

    /**
     * The registry tool for one MCP tool: namespaced spec, verbatim JSON
     * schema, execution forwarded over the connection; MCP failures surface
     * as ordinary tool errors (model-visible), never as harness crashes.
     */
    private static Tool bridgedTool(McpService service, String serverName,
            McpConnection.ToolInfo info) {
        String namespaced = "mcp__" + serverName + "__" + info.name();
        ToolSpec spec = new ToolSpec(namespaced,
                info.description() == null
                        ? "MCP tool \"" + info.name() + "\" on server \"" + serverName + "\""
                        : info.description(),
                info.inputSchema() instanceof ObjectNode object
                        ? object
                        : new ObjectNode(MAPPER.getNodeFactory()));
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult execute(ToolCall call) {
                try {
                    JsonNode arguments = call.arguments() == null || call.arguments().isBlank()
                            ? MAPPER.createObjectNode()
                            : MAPPER.readTree(call.arguments());
                    return ToolResult.ok(service.call(serverName, info.name(), arguments), Map.of());
                } catch (McpException e) {
                    return ToolResult.error(e.getMessage());
                } catch (IOException e) {
                    return ToolResult.error("mcp: cannot parse tool arguments: " + e.getMessage());
                }
            }
        };
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(ToolRegistry.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
