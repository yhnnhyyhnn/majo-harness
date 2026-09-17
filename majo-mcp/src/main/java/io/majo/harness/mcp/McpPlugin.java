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
 * Mounts the MCP client (roadmap-0.4 Phase 2, the dsh MCP analog): every
 * configured server is spawned over stdio, handshaken, and its
 * {@code tools/list} result is bridged into the {@code ToolRegistry} as
 * namespaced {@code mcp__<server>__<tool>} tools — they appear in
 * {@code /api/tools} and the generated tool catalog for free, and every call
 * rides the ordinary approval seam. Calls are bounded by the request
 * timeout; a server that fails to mount is logged loudly but does not break
 * the boot or the other servers.
 *
 * <p>Config: {@code {requestTimeoutSeconds: <n>, servers: {<name>: {command,
 * args: [...], env: {VAR_NAME: "${ENV_VAR}"}}}}} — env values reference
 * environment variables by name (credentials never live in the profile).
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
                if (row.get("command") == null) {
                    throw new IllegalArgumentException("mcp: server \"" + serverName
                            + "\" requires \"command\"");
                }
                String command = String.valueOf(row.get("command"));
                List<String> args = new ArrayList<>();
                if (row.get("args") instanceof List<?> list) {
                    for (Object item : list) {
                        args.add(String.valueOf(item));
                    }
                }
                McpConnection connection = McpConnection.connect(
                        serverName, command, args, envByName(row.get("env")), timeoutMillis);
                service.register(serverName, connection);
                for (McpConnection.ToolInfo info : connection.listTools()) {
                    registrations.add(tools.register(bridgedTool(service, serverName, info)));
                }
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

    /**
     * Env entries reference environment variables by name — {@code ${VAR}}
     * expands at mount time and an unset variable fails loudly (credentials
     * by name, never by value).
     */
    private static Map<String, String> envByName(Object value) {
        Map<String, String> env = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> map)) {
            return env;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String raw = String.valueOf(entry.getValue());
            String resolved = raw.startsWith("${") && raw.endsWith("}")
                    ? System.getenv(raw.substring(2, raw.length() - 1))
                    : raw;
            if (resolved == null) {
                throw new IllegalArgumentException("mcp: env \"" + entry.getKey()
                        + "\" references variable " + raw + " which is not set");
            }
            env.put(String.valueOf(entry.getKey()), resolved);
        }
        return env;
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
