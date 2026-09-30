package io.majo.harness.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

/**
 * {@code schedule_create} (dsh schedule): arms a per-session reminder —
 * {@code after_seconds} one-shot, {@code every_seconds} repeat (≥ 300), an
 * ISO local {@code at}, a {@code daily}/{@code weekly} recurring sugar, or a
 * Vixie five-field {@code cron} evaluated in an explicit IANA {@code timezone}
 * (dsh v0.2.0 parity). The prompt is delivered as a follow-up turn.
 */
public final class ScheduleCreateTool implements Tool {

    public static final String NAME = "schedule_create";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Schedule a reminder for this session: the prompt is sent back to you as a new "
                    + "turn at the due time. Pass exactly one timing shape: after_seconds "
                    + "(one-shot delay), every_seconds (repeat, minimum 300), at (ISO local "
                    + "date-time), daily (\"HH:mm\" — fires every day at that time), weekly "
                    + "({\"day\":\"MONDAY\",\"time\":\"HH:mm\"}), or cron (Vixie five-field "
                    + "expression, e.g. \"30 9 * * 1-5\", with optional timezone \"Asia/Shanghai\").",
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
        properties.putObject("daily").put("type", "string")
                .put("description", "Time of day HH:mm — fires every day");
        ObjectNode weekly = properties.putObject("weekly");
        weekly.put("type", "object");
        weekly.putObject("properties").putObject("day").put("type", "string")
                .put("description", "Day of week (MONDAY…SUNDAY)");
        weekly.putObject("properties").putObject("time").put("type", "string");
        weekly.putArray("required").add("day").add("time");
        properties.putObject("cron").put("type", "string")
                .put("description",
                        "Vixie five-field cron: minute hour day-of-month month day-of-week "
                                + "(*, lists, ranges, */step). Paired with optional timezone.");
        properties.putObject("timezone").put("type", "string")
                .put("description", "IANA timezone for cron (default: the host zone).");
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
            JsonNode args = MAPPER.readTree(call.arguments());
            String prompt = args == null || args.path("prompt").asText("").isBlank()
                    ? null : args.path("prompt").asText();
            if (prompt == null) {
                return ToolResult.error("schedule_create: \"prompt\" is required");
            }
            Long afterSeconds = args.get("after_seconds") != null
                    && args.get("after_seconds").isNumber()
                    ? args.get("after_seconds").asLong() : null;
            Long everySeconds = args.get("every_seconds") != null
                    && args.get("every_seconds").isNumber()
                    ? args.get("every_seconds").asLong() : null;
            String cronSpec = args.get("cron") != null && !args.get("cron").isNull()
                    ? args.get("cron").asText() : null;
            String timezoneId = args.get("timezone") != null && !args.get("timezone").isNull()
                    && !args.get("timezone").asText().isBlank()
                    ? args.get("timezone").asText() : null;
            Long atEpochMs = null;
            if (args.get("at") != null && !args.get("at").isNull()) {
                String at = args.get("at").asText();
                try {
                    atEpochMs = LocalDateTime.parse(at)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                } catch (java.time.format.DateTimeParseException e) {
                    try {
                        atEpochMs = java.time.Instant.parse(at).toEpochMilli();
                    } catch (DateTimeParseException ignored) {
                        return ToolResult.error("schedule_create: \"at\" must be ISO local "
                                + "date-time (yyyy-MM-ddTHH:mm:ss) or an ISO instant");
                    }
                }
            }

            // daily/weekly sugar: compute the next occurrence and delegate
            // to the existing recurring mechanism
            JsonNode daily = args.get("daily");
            JsonNode weekly = args.get("weekly");
            int timingShapes = (afterSeconds != null ? 1 : 0)
                    + (everySeconds != null ? 1 : 0)
                    + (atEpochMs != null ? 1 : 0)
                    + (daily != null && daily.isTextual() ? 1 : 0)
                    + (weekly != null && weekly.isObject() ? 1 : 0)
                    + (cronSpec != null ? 1 : 0);
            if (timingShapes > 1) {
                return ToolResult.error(
                        "schedule_create: pass exactly one timing shape");
            }
            if (cronSpec != null) {
                ScheduleService.Schedule schedule = schedules.create(
                        sessionId, prompt, null, null, null, cronSpec, timezoneId);
                return ToolResult.ok("cron schedule " + schedule.id + " ("
                        + cronSpec + (timezoneId == null ? "" : ", " + timezoneId)
                        + ") fires next at "
                        + java.time.Instant.ofEpochMilli(schedule.dueAtMs),
                        Map.of("id", schedule.id, "dueAt", schedule.dueAtMs,
                                "cron", schedule.cron));
            }
            if (daily != null && daily.isTextual()) {
                return createDaily(sessionId, prompt, daily.asText());
            }
            if (weekly != null && weekly.isObject()) {
                String day = weekly.path("day").asText("");
                String time = weekly.path("time").asText("");
                if (day.isBlank() || time.isBlank()) {
                    return ToolResult.error(
                            "schedule_create: weekly requires {day, time}");
                }
                return createWeekly(sessionId, prompt, day, time);
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

    /** Creates a daily recurring schedule at the specified local time. */
    private ToolResult createDaily(String sessionId, String prompt, String timeText) {
        try {
            LocalTime time = LocalTime.parse(timeText,
                    DateTimeFormatter.ofPattern("HH:mm"));
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime next = now.with(time);
            if (!next.isAfter(now)) {
                next = next.plusDays(1);
            }
            long dueAtMs = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            ScheduleService.Schedule schedule = schedules.create(
                    sessionId, prompt, null, dueAtMs, 86_400L);
            return ToolResult.ok("daily schedule " + schedule.id + " fires at "
                    + timeText + " (next: " + next.toLocalDate() + " "
                    + timeText + ")", Map.of("id", schedule.id, "dueAt", schedule.dueAtMs));
        } catch (java.time.format.DateTimeParseException e) {
            return ToolResult.error(
                    "schedule_create: daily must be \"HH:mm\" (e.g. \"09:00\")");
        }
    }

    /** Creates a weekly recurring schedule on the specified day and time. */
    private ToolResult createWeekly(String sessionId, String prompt,
            String dayText, String timeText) {
        try {
            DayOfWeek day = DayOfWeek.valueOf(dayText.toUpperCase());
            LocalTime time = LocalTime.parse(timeText,
                    DateTimeFormatter.ofPattern("HH:mm"));
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime next = now.with(java.time.temporal.TemporalAdjusters.nextOrSame(day))
                    .with(time);
            if (!next.isAfter(now)) {
                next = next.with(TemporalAdjusters.next(day));
            }
            long dueAtMs = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            ScheduleService.Schedule schedule = schedules.create(
                    sessionId, prompt, null, dueAtMs, 604_800L);
            return ToolResult.ok("weekly schedule " + schedule.id + " fires every "
                    + day + " at " + timeText + " (next: " + next.toLocalDate() + ")",
                    Map.of("id", schedule.id, "dueAt", schedule.dueAtMs));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("schedule_create: weekly day must be MONDAY…SUNDAY, "
                    + "time must be \"HH:mm\"");
        }
    }
}
