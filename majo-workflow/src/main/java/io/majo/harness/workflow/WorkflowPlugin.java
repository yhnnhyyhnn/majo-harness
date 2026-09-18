package io.majo.harness.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.session.SessionService;
import io.majo.harness.subagent.SubagentService;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Mounts the workflow runtime (roadmap-0.5, design docs/workflow-design.md):
 * definitions load from a {@code workflows/} directory (YAML), and one
 * model-facing {@code workflow_run} tool exposes them — its spec description
 * enumerates the workflows and carries the {@link Tool#ALLOW_MODEL_TRIGGER_TAG}
 * exemption marker for definitions that declare
 * {@code allowModelTrigger: true} (otherwise the tool rides the ordinary
 * approval gate, e.g. listed in the {@code tool-approval} row). Steps run in
 * scoped child sessions; the requesting session keeps only bookkeeping.
 *
 * <p>Config: {@code {dir: "workflows"}}.
 */
public final class WorkflowPlugin implements Plugin {

    public static final String NAME = "workflow";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Object apply(Context ctx, Object config) {
        Map<?, ?> map = config instanceof Map<?, ?> m ? m : Map.of();
        Path directory = Path.of(map.get("dir") == null ? "workflows" : String.valueOf(map.get("dir")));
        SessionService sessions = ctx.get(SessionService.NAME);
        SubagentService subagent = ctx.get(SubagentService.NAME);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        WorkflowService workflow;
        try {
            workflow = new WorkflowService(ctx, sessions, subagent, directory);
        } catch (IOException e) {
            throw new IllegalStateException("workflow: cannot read directory " + directory, e);
        }
        return tools.register(new WorkflowRunTool(workflow));
    }

    /** The model trigger: {@code workflow_run} with {name, args}. */
    private static final class WorkflowRunTool implements Tool {

        private final WorkflowService workflow;

        WorkflowRunTool(WorkflowService workflow) {
            this.workflow = workflow;
        }

        @Override
        public ToolSpec spec() {
            StringBuilder description = new StringBuilder(
                    "Run a named workflow (multi-step orchestration defined by the host). "
                            + "Available workflows:");
            for (String name : workflow.names()) {
                WorkflowDefinition definition = workflow.definition(name);
                description.append("\n- ").append(name);
                if (definition != null && definition.description() != null) {
                    description.append(" — ").append(definition.description());
                }
                if (definition != null && definition.allowModelTrigger()) {
                    description.append(" ").append(Tool.ALLOW_MODEL_TRIGGER_TAG);
                }
            }
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            properties.putObject("name").put("type", "string");
            properties.putObject("args").put("type", "object");
            schema.putArray("required").add("name");
            return new ToolSpec("workflow_run", description.toString(), schema);
        }

        @Override
        public ToolResult execute(ToolCall call) {
            try {
                JsonNode args = call.arguments() == null || call.arguments().isBlank()
                        ? MAPPER.createObjectNode()
                        : MAPPER.readTree(call.arguments());
                String name = args.path("name").asText("");
                if (name.isBlank()) {
                    return ToolResult.error("workflow_run: pass a workflow name");
                }
                Map<String, String> runArgs = new HashMap<>();
                JsonNode rawArgs = args.path("args");
                if (rawArgs.isObject()) {
                    rawArgs.properties().forEach(entry ->
                            runArgs.put(entry.getKey(), String.valueOf(entry.getValue())));
                }
                String summary = workflow.runForCaller(name, runArgs);
                return ToolResult.ok(summary, Map.of());
            } catch (IOException e) {
                return ToolResult.error("workflow_run: cannot parse arguments: " + e.getMessage());
            } catch (RuntimeException e) {
                return ToolResult.error("workflow_run: " + e.getMessage());
            }
        }
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(SessionService.NAME, null);
        inject.put(SubagentService.NAME, null);
        inject.put(ToolRegistry.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
