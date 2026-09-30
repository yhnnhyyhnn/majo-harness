package io.majo.harness.ptc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Mounts the PTC runtime (dsh `ptc-runtime` analog): a model-facing
 * {@code run_code} tool that executes a JavaScript program in a fresh
 * Node.js process. The core value: instead of N discrete tool calls
 * (each costing a full LLM round-trip), the model writes one program that
 * does all N steps — calling host tools directly via {@code tools.<name>(args)}
 * through a JSON-lines control protocol (nested dispatch, approval gates apply).
 *
 * <p>Config: {@code {nodePath: "node", timeoutSeconds: 30, maxToolCalls: 64}}.
 */
public final class PtcPlugin implements Plugin {

    public static final String NAME = "ptc";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Object apply(Context ctx, Object config) {
        PtcService service = new PtcService(ctx, config);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        return tools.register(new RunCodeTool(service, tools));
    }

    /** The model-facing {@code run_code} tool. */
    private static final class RunCodeTool implements Tool {

        private final PtcService service;
        private final ToolRegistry tools;

        RunCodeTool(PtcService service, ToolRegistry tools) {
            this.service = service;
            this.tools = tools;
        }

        @Override
        public ToolSpec spec() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties").putObject("code").put("type", "string")
                    .put("description",
                            "JavaScript (ES module). Host tools are callable: "
                                    + "await tools.<name>(args) — e.g. await tools.read_file({path}). "
                                    + "Complete with result(value); console.log output is returned "
                                    + "instead when the program never calls result().");
            schema.putArray("required").add("code");
            return new ToolSpec("run_code",
                    "Executes a JavaScript program in a fresh Node.js process and returns its "
                            + "result. The program can call host tools directly — "
                            + "await tools.read_file({path: '...'}) — so one program replaces N "
                            + "tool round-trips (multi-step computation, JSON transformation, "
                            + "batch file inspection). Nested calls pass the same approval "
                            + "gates as direct calls. Complete with result(value); "
                            + "console.log output is returned instead when the program never "
                            + "calls result().",
                    schema);
        }

        @Override
        public ToolResult execute(ToolCall call) {
            try {
                JsonNode args = parseArgs(call);
                String code = args.path("code").asText("");
                if (code.isBlank()) {
                    return ToolResult.error("run_code: pass a code string");
                }
                String result = service.execute(code, tools);
                return ToolResult.ok(result, Map.of());
            } catch (PtcException e) {
                return ToolResult.error(e.getMessage());
            } catch (IOException e) {
                return ToolResult.error("run_code: cannot parse arguments: " + e.getMessage());
            }
        }
    }

    private static JsonNode parseArgs(ToolCall call) throws IOException {
        return call.arguments() == null || call.arguments().isBlank()
                ? MAPPER.createObjectNode()
                : MAPPER.readTree(call.arguments());
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
