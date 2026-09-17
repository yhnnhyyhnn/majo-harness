package io.majo.harness.jobs;

import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code job_list}: the session's jobs with ids, states, and timings. */
public final class JobListTool implements Tool {

    public static final String NAME = "job_list";

    private final JobsService jobs;

    public JobListTool(JobsService jobs) {
        this.jobs = jobs;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.of(NAME,
                "List this session's background jobs: id, state (running/completed/failed/killed), "
                        + "script, and timing.");
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String sessionId = InteractionContext.sessionId();
        if (sessionId == null) {
            return ToolResult.error("job_list runs inside a turn; no session is bound");
        }
        List<JobsService.Job> jobs = this.jobs.list(sessionId);
        if (jobs.isEmpty()) {
            return ToolResult.ok("no background jobs in this session", Map.of("count", 0));
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (JobsService.Job job : jobs) {
            String line = job.id + " [" + job.state.name().toLowerCase() + "] " + job.script;
            text.append(line).append('\n');
            entries.add(Map.of(
                    "id", job.id,
                    "state", job.state.name().toLowerCase(),
                    "startedAt", job.startedAtMs,
                    "exitCode", job.exitCode));
        }
        return ToolResult.ok(text.toString().stripTrailing(), Map.of("jobs", entries));
    }
}
