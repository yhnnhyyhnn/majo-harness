package io.majo.harness.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.util.Map;

/**
 * {@code job_output}: reads a job's captured output tail, optionally waiting
 * (up to {@code wait_seconds}) for a running job to finish first.
 */
public final class JobOutputTool implements Tool {

    public static final String NAME = "job_output";
    private static final long MAX_WAIT_SECONDS = 60;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Read a background job's output. Pass wait_seconds (up to 60) to wait for a "
                    + "running job to finish before reading.",
            schema());

    private final JobsService jobs;

    public JobOutputTool(JobsService jobs) {
        this.jobs = jobs;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("id").put("type", "string");
        properties.putObject("wait_seconds").put("type", "number");
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
            return ToolResult.error("job_output runs inside a turn; no session is bound");
        }
        try {
            JsonNode arguments = MAPPER.readTree(call.arguments());
            String id = arguments == null || arguments.get("id") == null
                    ? null : arguments.get("id").asText();
            JobsService.Job job = id == null ? null : jobs.get(sessionId, id);
            if (job == null) {
                return ToolResult.error("job_output: unknown job \"" + id + "\"");
            }
            long waitMillis = 0;
            if (arguments.get("wait_seconds") != null && arguments.get("wait_seconds").isNumber()) {
                waitMillis = Math.min(MAX_WAIT_SECONDS, arguments.get("wait_seconds").asLong())
                        * 1000L;
            }
            long deadline = System.currentTimeMillis() + Math.max(0, waitMillis);
            while (job.state == JobsService.State.RUNNING && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            String status = job.state == JobsService.State.RUNNING
                    ? "still running"
                    : job.state.name().toLowerCase() + " (exit " + job.exitCode + ")";
            return ToolResult.ok("job " + job.id + " " + status
                    + (job.output.isBlank() ? "" : ":\n" + job.output),
                    Map.of("jobId", job.id, "state", job.state.name()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("job_output: interrupted");
        } catch (Exception e) {
            return ToolResult.error("job_output: cannot parse arguments: " + e.getMessage());
        }
    }
}
