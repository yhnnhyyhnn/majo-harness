package io.majo.harness.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;

/**
 * {@code send_message} (dsh tool-subagent-control parity): continues a live
 * child agent with a follow-up message. Synchronous at majo scale — the
 * child's next turn runs now and its answer returns, so the parent can act
 * on it in the same turn.
 */
public final class SendMessageTool implements Tool {

    public static final String NAME = "send_message";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SubagentService subagent;

    public SendMessageTool(SubagentService subagent) {
        this.subagent = subagent;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("session_id").put("type", "string")
                .put("description", "The child agent's session id (from delegate_task).");
        properties.putObject("message").put("type", "string")
                .put("description", "The follow-up message for the child agent.");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("session_id").add("message");
        return schema;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(NAME,
                "Sends a follow-up message to a child agent you previously delegated to "
                        + "(delegate_task returns its session_id). The child continues with its "
                        + "prior history and its answer comes back.",
                schema());
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = MAPPER.readTree(call.arguments());
            String sessionId = args.path("session_id").asText("");
            String message = args.path("message").asText("");
            if (sessionId.isBlank() || message.isBlank()) {
                return ToolResult.error("send_message: session_id and message are required");
            }
            SubagentService.DelegationOutcome outcome = subagent.sendMessage(sessionId, message);
            return ToolResult.ok(outcome.answer(),
                    java.util.Map.of("childSessionId", outcome.childSessionId()));
        } catch (SubagentException | IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("send_message: cannot parse arguments: " + e.getMessage());
        }
    }
}
