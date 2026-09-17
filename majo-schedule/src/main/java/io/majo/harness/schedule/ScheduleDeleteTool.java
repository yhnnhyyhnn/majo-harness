package io.majo.harness.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;

/** {@code schedule_delete}: cancels a scheduled reminder durably. */
public final class ScheduleDeleteTool implements Tool {

    public static final String NAME = "schedule_delete";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Cancel a scheduled reminder by id (durable: it never fires after this).",
            schema());

    private final ScheduleService schedules;

    public ScheduleDeleteTool(ScheduleService schedules) {
        this.schedules = schedules;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("id").put("type", "string");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("id");
        return schema;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String sessionId = InteractionContext.sessionId();
        if (sessionId == null) {
            return ToolResult.error("schedule_delete runs inside a turn; no session is bound");
        }
        try {
            JsonNode arguments = MAPPER.readTree(call.arguments());
            String id = arguments == null || arguments.get("id") == null
                    ? null : arguments.get("id").asText();
            boolean deleted = id != null && schedules.delete(sessionId, id);
            return deleted
                    ? ToolResult.ok("schedule " + id + " cancelled", java.util.Map.of("id", id))
                    : ToolResult.error("schedule_delete: unknown schedule \"" + id + "\"");
        } catch (Exception e) {
            return ToolResult.error("schedule_delete: cannot parse arguments: " + e.getMessage());
        }
    }
}
