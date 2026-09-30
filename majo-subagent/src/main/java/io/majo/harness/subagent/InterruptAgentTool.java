package io.majo.harness.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;

/**
 * {@code interrupt_agent} (dsh tool-subagent-control parity): requests
 * cooperative cancellation of a child agent's running turn (the loop's
 * minimal-cancellation abort — the turn closes at its next step boundary).
 */
public final class InterruptAgentTool implements Tool {

    public static final String NAME = "interrupt_agent";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SubagentService subagent;

    public InterruptAgentTool(SubagentService subagent) {
        this.subagent = subagent;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("session_id").put("type", "string")
                .put("description", "The child agent's session id (see list_agents).");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("session_id");
        return schema;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(NAME,
                "Interrupts a child agent's running turn (cooperative: it closes at the next "
                        + "step boundary). The child stays continuable — send_message works after.",
                schema());
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = MAPPER.readTree(call.arguments());
            String sessionId = args.path("session_id").asText("");
            if (sessionId.isBlank()) {
                return ToolResult.error("interrupt_agent: session_id is required");
            }
            if (!subagent.interrupt(sessionId)) {
                return ToolResult.error("interrupt_agent: unknown child session \"" + sessionId + "\"");
            }
            return ToolResult.ok("interrupt requested for " + sessionId
                    + " (the running turn closes at its next step boundary)",
                    java.util.Map.of("childSessionId", sessionId));
        } catch (Exception e) {
            return ToolResult.error("interrupt_agent: cannot parse arguments: " + e.getMessage());
        }
    }
}
