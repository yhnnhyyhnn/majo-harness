package io.majo.harness.subprocess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Model-facing consumer of the subprocess seam: runs an argv list (no shell)
 * and reports stdout on success, or an error result carrying the exit code and
 * stderr. Policy/provider failures surface as an error result, not a crash.
 */
public final class RunCommandTool implements Tool {

    public static final String NAME = "run_command";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Runs a command as an argv list (executable plus arguments; no shell interpolation) "
                    + "and returns its stdout, or the exit code and stderr on failure.",
            schema());

    private final SubprocessService subprocess;

    public RunCommandTool(SubprocessService subprocess) {
        this.subprocess = subprocess;
    }

    private static JsonNode schema() {
        ArrayNode items = MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("type", "string"));
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("argv").put("type", "array").set("items", items);
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("argv");
        return schema;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode arguments = MAPPER.readTree(call.arguments());
            if (arguments == null || !arguments.hasNonNull("argv") || !arguments.get("argv").isArray()) {
                return ToolResult.error("run_command: missing \"argv\" string array argument");
            }
            List<String> argv = new ArrayList<>();
            for (JsonNode item : arguments.get("argv")) {
                if (!item.isTextual()) {
                    return ToolResult.error("run_command: argv entries must be strings");
                }
                argv.add(item.asText());
            }
            ProcessResult result = subprocess.run(Command.of(argv));
            if (result.ok()) {
                return ToolResult.ok(result.stdout().stripTrailing());
            }
            String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
            return ToolResult.error("run_command exited " + result.exitCode()
                    + ": " + detail.strip());
        } catch (SubprocessException e) {
            return ToolResult.error("run_command: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("run_command: cannot parse arguments: " + e.getMessage());
        }
    }
}
