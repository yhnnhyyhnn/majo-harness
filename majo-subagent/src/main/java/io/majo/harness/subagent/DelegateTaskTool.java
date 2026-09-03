package io.majo.harness.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;

/**
 * Model-facing consumer of the subagent seam: delegates a task to a child
 * session and returns the child's final text. Depth/policy failures surface as
 * an error result, not a crash.
 */
public final class DelegateTaskTool implements Tool {

    public static final String NAME = "delegate_task";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Delegates a task to a child agent with a fresh session and returns its final answer.",
            schema());

    private final SubagentService subagent;

    public DelegateTaskTool(SubagentService subagent) {
        this.subagent = subagent;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("task").put("type", "string");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("task");
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
            if (arguments == null || arguments.get("task") == null) {
                return ToolResult.error("delegate_task: missing \"task\" argument");
            }
            SubagentService.DelegationOutcome outcome = subagent.delegateWithChild(
                    arguments.get("task").asText());
            return ToolResult.ok(outcome.answer(), java.util.Map.of("childSessionId", outcome.childSessionId()));
        } catch (SubagentException e) {
            return ToolResult.error("delegate_task: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("delegate_task: cannot parse arguments: " + e.getMessage());
        }
    }
}
