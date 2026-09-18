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
 * <p>Config: {@code {requestTimeoutSeconds: <n>, failOnStartupError: <bool>,
 * reconnect: {enabled, initialDelayMs, maxDelayMs, maxAttempts}, servers:
 * {<name>: {command, args, env} | {url, headers}}}} — env/header values
 * reference environment variables by name (credentials never live in the
 * profile); HTTP auth is explicit headers only, no OAuth. Reconnect defaults
 * on (500ms→30s backoff, 10 attempts, 60s stability reset); startup failures
 * are logged loudly but non-fatal unless {@code failOnStartupError}.
 */
public final class McpPlugin implements Plugin {

    public static final String NAME = "mcp";
    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 10;

    static final Logger LOG = LoggerFactory.getLogger(McpPlugin.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Object apply(Context ctx, Object config) {
        Map<?, ?> map = config instanceof Map<?, ?> m ? m : Map.of();
        long timeoutMillis = map.get("requestTimeoutSeconds") instanceof Number number
                && number.longValue() > 0
                ? number.longValue() * 1000L
                : DEFAULT_REQUEST_TIMEOUT_SECONDS * 1000L;
        boolean failOnStartupError = Boolean.TRUE.equals(map.get("failOnStartupError"));
        boolean reconnectEnabled = true;
        long reconnectInitial = 500;
        long reconnectMax = 30_000;
        int reconnectAttempts = 10;
        if (map.get("reconnect") instanceof Map<?, ?> reconnect) {
            if (reconnect.get("enabled") instanceof Boolean enabled) {
                reconnectEnabled = enabled;
            }
            if (reconnect.get("initialDelayMs") instanceof Number number && number.longValue() > 0) {
                reconnectInitial = number.longValue();
            }
            if (reconnect.get("maxDelayMs") instanceof Number number && number.longValue() > 0) {
                reconnectMax = number.longValue();
            }
            if (reconnect.get("maxAttempts") instanceof Number number && number.intValue() > 0) {
                reconnectAttempts = number.intValue();
            }
        }
        Map<?, ?> servers = map.get("servers") instanceof Map<?, ?> s ? s : Map.of();

        McpService service = new McpService(ctx);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        List<Disposable> registrations = new ArrayList<>();
        for (Map.Entry<?, ?> entry : servers.entrySet()) {
            String serverName = String.valueOf(entry.getKey());
            Map<?, ?> row = entry.getValue() instanceof Map<?, ?> r ? r : Map.of();
            try {
                if (!serverName.matches("[A-Za-z0-9_-]{1,32}")) {
                    throw new IllegalArgumentException("mcp: server name \"" + serverName
                            + "\" must match [A-Za-z0-9_-]{1,32}");
                }
                McpConnection.Factory factory =
                        () -> McpConnection.open(serverName, row, timeoutMillis);
                // startup failure is handled by failOnStartupError, NOT the
                // reconnect budget: open once directly, then wrap
                McpConnection initial = factory.open();
                McpConnection connection = reconnectEnabled
                        ? new ReconnectingConnection(serverName, factory, initial,
                                reconnectInitial, reconnectMax, reconnectAttempts)
                        : initial;
                service.register(serverName, connection);
                for (McpConnection.ToolInfo info : connection.listTools()) {
                    registrations.add(tools.register(bridgedTool(service, serverName, info)));
                }
                registrations.addAll(promptTools(service, tools, serverName, connection));
                LOG.info("mcp: mounted server \"{}\" with {} tool(s)", serverName,
                        service.tools(serverName).size());
            } catch (RuntimeException | IOException e) {
                if (failOnStartupError) {
                    if (e instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new IllegalStateException("mcp: server \"" + serverName
                            + "\" failed to mount", e);
                }
                LOG.error("mcp: server \"{}\" failed to mount: {}", serverName, e.getMessage(), e);
            }
        }
        // dsh mcp-resources shape: shared tools with a server argument
        // (aligned 2026-09; replaces the earlier per-server read-only tool)
        if (!service.servers().isEmpty()) {
            registrations.add(tools.register(sharedResourcesTool(service)));
            registrations.add(tools.register(templatesTool(service)));
            registrations.add(tools.register(readResourceTool(service)));
        }
        // dsh server-context shape: each server's instructions (plus the
        // usable-server list) contribute a system-prompt section
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        if (loop != null) {
            registrations.add(loop.registerSystemSection("mcp", () -> sectionText(service)));
        }
        registrations.add(new Disposable() {
            @Override
            public void dispose() {
                service.closeAll();
            }
        });
        return Disposables.composite(registrations);
    }

    /** The system section: usable servers plus per-server instructions. */
    private static String sectionText(McpService service) {
        List<String> servers = service.servers();
        if (servers.isEmpty()) {
            return null;
        }
        StringBuilder section = new StringBuilder("Connected MCP servers: ")
                .append(String.join(", ", servers))
                .append(". Their tools are the mcp__<server>__<tool> entries; "
                        + "resources are readable via read_mcp_resource.");
        for (String server : servers) {
            String instructions = service.instructions(server);
            if (instructions != null && !instructions.isBlank()) {
                section.append("\n\n## mcp:").append(server).append("\n")
                        .append(instructions);
            }
        }
        return section.toString();
    }

    /** Aggregates one node-list method ({@code resources/list} etc.) across servers. */
    private static String aggregate(McpService service, String specifiedServer,
            String arrayName, java.util.function.Function<String, JsonNode> call) {
        List<String> servers = specifiedServer == null || specifiedServer.isBlank()
                ? service.servers()
                : List.of(specifiedServer);
        StringBuilder text = new StringBuilder();
        for (String server : servers) {
            JsonNode result = call.apply(server);
            for (JsonNode entry : result.path(arrayName)) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append('[').append(server).append("] ").append(entry);
            }
        }
        return text.length() == 0 ? "(none)" : text.toString();
    }

    /** Shared {@code list_mcp_resources}: every server's resources (server arg optional). */
    private static Tool sharedResourcesTool(McpService service) {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                ObjectNode schema = MAPPER.createObjectNode();
                schema.put("type", "object");
                schema.putObject("properties").putObject("server").put("type", "string");
                return new ToolSpec("list_mcp_resources",
                        "Lists the resources exposed by connected MCP servers "
                                + "(optional server filter).",
                        schema);
            }

            @Override
            public ToolResult execute(ToolCall call) {
                try {
                    JsonNode args = parseArgs(call);
                    return ToolResult.ok(aggregate(service, args.path("server").asText(null),
                            "resources", service::listResources), Map.of());
                } catch (McpException e) {
                    return ToolResult.error(e.getMessage());
                } catch (IOException e) {
                    return ToolResult.error("mcp: cannot parse arguments: " + e.getMessage());
                }
            }
        };
    }

    /** Shared {@code list_mcp_resource_templates}: URI templates across servers. */
    private static Tool templatesTool(McpService service) {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                ObjectNode schema = MAPPER.createObjectNode();
                schema.put("type", "object");
                schema.putObject("properties").putObject("server").put("type", "string");
                return new ToolSpec("list_mcp_resource_templates",
                        "Lists the resource templates (parameterized URIs) exposed by "
                                + "connected MCP servers (optional server filter).",
                        schema);
            }

            @Override
            public ToolResult execute(ToolCall call) {
                try {
                    JsonNode args = parseArgs(call);
                    return ToolResult.ok(aggregate(service, args.path("server").asText(null),
                            "resourceTemplates", service::listTemplates), Map.of());
                } catch (McpException e) {
                    return ToolResult.error(e.getMessage());
                } catch (IOException e) {
                    return ToolResult.error("mcp: cannot parse arguments: " + e.getMessage());
                }
            }
        };
    }

    /** Shared {@code read_mcp_resource}: reads one resource by server + uri. */
    private static Tool readResourceTool(McpService service) {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                ObjectNode schema = MAPPER.createObjectNode();
                schema.put("type", "object");
                ObjectNode properties = schema.putObject("properties");
                properties.putObject("server").put("type", "string");
                properties.putObject("uri").put("type", "string");
                schema.putArray("required").add("server").add("uri");
                return new ToolSpec("read_mcp_resource",
                        "Reads one MCP resource by server and uri; returns the resource "
                                + "contents.",
                        schema);
            }

            @Override
            public ToolResult execute(ToolCall call) {
                try {
                    JsonNode args = parseArgs(call);
                    String server = args.path("server").asText("");
                    String uri = args.path("uri").asText("");
                    if (server.isBlank() || uri.isBlank()) {
                        return ToolResult.error("read_mcp_resource: pass server and uri");
                    }
                    JsonNode contents = service.readResource(server, uri).path("contents");
                    if (!contents.isArray() || contents.isEmpty()) {
                        return ToolResult.error("read_mcp_resource: empty contents for "
                                + uri);
                    }
                    return ToolResult.ok(
                            contents.path(0).path("text").asText(""), Map.of());
                } catch (McpException e) {
                    return ToolResult.error(e.getMessage());
                } catch (IOException e) {
                    return ToolResult.error("mcp: cannot parse arguments: " + e.getMessage());
                }
            }
        };
    }

    private static JsonNode parseArgs(ToolCall call) throws IOException {
        return call.arguments() == null || call.arguments().isBlank()
                ? MAPPER.createObjectNode()
                : MAPPER.readTree(call.arguments());
    }

    /**
     * Per-server prompt tool (majo keeps prompts as namespaced read-only
     * tools — a documented divergence: dsh does not bridge prompts at all).
     * Failures here are loud but non-fatal (tools stay).
     */
    private static List<Disposable> promptTools(McpService service, ToolRegistry tools,
            String serverName, McpConnection connection) {
        List<Disposable> registrations = new ArrayList<>();
        if (!connection.capabilities().path("prompts").isObject()) {
            return registrations;
        }
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
        inject.put(io.majo.harness.agent.loop.AgentLoopService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
