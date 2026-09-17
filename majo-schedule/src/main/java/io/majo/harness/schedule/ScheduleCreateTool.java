package io.majo.harness.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.util.Map;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * {@code schedule_create} (dsh schedule): arms a per-session reminder —
 * {@code after_seconds} one-shot, {@code every_seconds} repeat (>= 300), or
 * an ISO local {@code at}. The prompt is delivered as a follow-up turn.
 */
public final class ScheduleCreateTool implements Tool {

    public static final String NAME = "schedule_create";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Schedule a reminder for this session: the prompt is sent back to you as a new "
                    + "turn at the due time. Pass exactly one of after_seconds (one-shot delay), "
                    + "every_seconds (repeat, minimum 300), or at (ISO local date-time, "
                    + "yyyy-MM-ddTHH:mm:ss).",
            schema());

    private final ScheduleService schedules;

    public ScheduleCreateTool(ScheduleService schedules) {
        this.schedules = schedules;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("prompt").put("type", "string");
        properties.putObject("after_seconds").put("type", "number");
        properties.putObject("every_seconds").put("type", "number");
        properties.putObject("at").put("type", "string");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("prompt");
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
            return ToolResult.error("schedule_create runs inside a turn; no session is bound");
        }
        try {
            JsonNode arguments = MAPPER.readTree(call.arguments());
            String prompt = arguments == null || arguments.path("prompt").asText("").isBlank()
                    ? null : arguments.path("prompt").asText();
            if (prompt == null) {
                return ToolResult.error("schedule_create: \"prompt\" is required");
            }
            Long afterSeconds = arguments.get("after_seconds") != null
                    && arguments.get("after_seconds").isNumber()
                    ? arguments.get("after_seconds").asLong() : null;
            Long everySeconds = arguments.get("every_seconds") != null
                    && arguments.get("every_seconds").isNumber()
                    ? arguments.get("every_seconds").asLong() : null;
            Long atEpochMs = null;
            if (arguments.get("at") != null && !arguments.get("at").isNull()) {
                String at = arguments.get("at").asText();
                try {
                    atEpochMs = LocalDateTime.parse(at)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                } catch (DateTimeParseException e) {
                    try {
                        atEpochMs = Instant.parse(at).toEpochMilli();
                    } catch (DateTimeParseException ignored) {
                        return ToolResult.error("schedule_create: \"at\" must be ISO local "
                                + "date-time (yyyy-MM-ddTHH:mm:ss) or an ISO instant");
                    }
                }
            }
            ScheduleService.Schedule schedule = schedules.create(
                    sessionId, prompt, afterSeconds, atEpochMs, everySeconds);
            String dueAt = java.time.Instant.ofEpochMilli(schedule.dueAtMs).toString();
            return ToolResult.ok("scheduled " + schedule.id + " for " + dueAt
                    + (schedule.intervalSeconds > 0
                            ? " (repeats every " + schedule.intervalSeconds + "s)" : ""),
                    Map.of("id", schedule.id, "dueAt", schedule.dueAtMs));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("schedule_create: cannot parse arguments: " + e.getMessage());
        }
    }
}
