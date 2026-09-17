package io.majo.harness.schedule;

import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.time.Instant;
import java.util.Map;

/** {@code schedule_list}: the session's active schedules, soonest first. */
public final class ScheduleListTool implements Tool {

    public static final String NAME = "schedule_list";

    private final ScheduleService schedules;

    public ScheduleListTool(ScheduleService schedules) {
        this.schedules = schedules;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.of(NAME,
                "List this session's active scheduled reminders: id, due time, repeat interval, "
                        + "and the prompt that will be delivered.");
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String sessionId = InteractionContext.sessionId();
        if (sessionId == null) {
            return ToolResult.error("schedule_list runs inside a turn; no session is bound");
        }
        var active = schedules.list(sessionId);
        if (active.isEmpty()) {
            return ToolResult.ok("no scheduled reminders", Map.of("count", 0));
        }
        StringBuilder text = new StringBuilder();
        for (ScheduleService.Schedule schedule : active) {
            text.append(schedule.id).append(" → ")
                    .append(Instant.ofEpochMilli(schedule.dueAtMs))
                    .append(schedule.intervalSeconds > 0
                            ? " (every " + schedule.intervalSeconds + "s)" : "")
                    .append(": ").append(schedule.prompt).append('\n');
        }
        return ToolResult.ok(text.toString().stripTrailing(), Map.of("count", active.size()));
    }
}
