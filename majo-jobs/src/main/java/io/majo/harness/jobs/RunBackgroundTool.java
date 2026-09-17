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
 * {@code run_background} (dsh bash {@code run_in_background}): spawns a shell
 * script as a session job and returns its job id immediately — the completion
 * notice arrives as its own turn (idle → wake, busy → next turn).
 */
public final class RunBackgroundTool implements Tool {

    public static final String NAME = "run_background";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Run a command-line script in the background and return immediately. The result "
                    + "arrives as a message when the script finishes; inspect it earlier with "
                    + "job_output, list jobs with job_list, stop one with job_kill.",
            schema());

    private final JobsService jobs;

    public RunBackgroundTool(JobsService jobs) {
        this.jobs = jobs;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("script").put("type", "string");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("script");
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
            return ToolResult.error("run_background runs inside a turn; no session is bound");
        }
        try {
            JsonNode arguments = MAPPER.readTree(call.arguments());
            String script = arguments == null || arguments.get("script") == null
                    ? null : arguments.get("script").asText();
            if (script == null || script.isBlank()) {
                return ToolResult.error("run_background: missing \"script\" argument");
            }
            JobsService.Job job = jobs.start(sessionId, script);
            return ToolResult.ok("started background job " + job.id
                    + " — you will be notified when it finishes", Map.of("jobId", job.id));
        } catch (Exception e) {
            return ToolResult.error("run_background: " + e.getMessage());
        }
    }
}
