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

/** {@code job_kill}: destroys a running job's process. */
public final class JobKillTool implements Tool {

    public static final String NAME = "job_kill";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Stop a running background job by id (its process is destroyed).",
            schema());

    private final JobsService jobs;

    public JobKillTool(JobsService jobs) {
        this.jobs = jobs;
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
            return ToolResult.error("job_kill runs inside a turn; no session is bound");
        }
        try {
            JsonNode arguments = MAPPER.readTree(call.arguments());
            String id = arguments == null || arguments.get("id") == null
                    ? null : arguments.get("id").asText();
            if (id == null || jobs.get(sessionId, id) == null) {
                return ToolResult.error("job_kill: unknown job \"" + id + "\"");
            }
            boolean stopped = jobs.kill(sessionId, id);
            return stopped
                    ? ToolResult.ok("job " + id + " stopped", Map.of("jobId", id))
                    : ToolResult.error("job " + id + " is not running (nothing to kill)");
        } catch (Exception e) {
            return ToolResult.error("job_kill: cannot parse arguments: " + e.getMessage());
        }
    }
}
