package io.majo.harness.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.io.IOException;
import java.util.Map;

/**
 * The model-facing {@code schedule_update} tool (dsh parity): edits an
 * existing schedule's prompt and/or timing, re-arms with the new parameters.
 */
public final class ScheduleUpdateTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ScheduleService schedule;

    public ScheduleUpdateTool(ScheduleService schedule) {
        this.schedule = schedule;
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("id").put("type", "string");
        props.putObject("prompt").put("type", "string");
        props.putObject("after_seconds").put("type", "number");
        props.putObject("at_epoch_ms").put("type", "number");
        props.putObject("every_seconds").put("type", "number");
        props.putObject("cron").put("type", "string")
                .put("description", "Vixie five-field cron expression.");
        props.putObject("timezone").put("type", "string")
                .put("description", "IANA timezone for cron (default: the host zone).");
        schema.putArray("required").add("id");
        return new ToolSpec("schedule_update",
                "Updates an existing scheduled reminder: change the prompt and/or timing "
                        + "(exactly one of after_seconds, at_epoch_ms, every_seconds, cron).",
                schema);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = call.arguments() == null || call.arguments().isBlank()
                    ? MAPPER.createObjectNode()
                    : MAPPER.readTree(call.arguments());
            String id = args.path("id").asText("");
            if (id.isBlank()) {
                return ToolResult.error("schedule_update: pass a schedule id");
            }
            String sessionId = io.majo.harness.interaction.InteractionContext.sessionId();
            if (sessionId == null) {
                return ToolResult.error("schedule_update: no active session");
            }
            // dsh parity: delegated children cannot create or reschedule reminders
            if (io.majo.harness.interaction.InteractionContext.delegationDepth() > 0) {
                return ToolResult.error("schedule_update: a delegated subagent cannot use "
                        + "reminders — schedule from the root conversation instead");
            }
            String prompt = args.hasNonNull("prompt") ? args.get("prompt").asText() : null;
            Long afterSeconds = args.hasNonNull("after_seconds")
                    ? args.get("after_seconds").asLong() : null;
            Long atEpochMs = args.hasNonNull("at_epoch_ms")
                    ? args.get("at_epoch_ms").asLong() : null;
            Long everySeconds = args.hasNonNull("every_seconds")
                    ? args.get("every_seconds").asLong() : null;
            String cronSpec = args.hasNonNull("cron") ? args.get("cron").asText() : null;
            String timezoneId = args.hasNonNull("timezone") ? args.get("timezone").asText() : null;
            ScheduleService.Schedule updated = schedule.update(sessionId, id, prompt,
                    afterSeconds, atEpochMs, everySeconds, cronSpec, timezoneId);
            return ToolResult.ok("schedule " + id + " updated: next due "
                    + java.time.Instant.ofEpochMilli(updated.dueAtMs), Map.of());
        } catch (IOException e) {
            return ToolResult.error("schedule_update: cannot parse arguments: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
