package io.majo.harness.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;

/**
 * Model-facing consumer of the shell seam: runs a command-line script and
 * reports stdout on success, or an error result carrying the exit code and
 * stderr. Policy/provider failures surface as an error result, not a crash.
 */
public final class RunShellTool implements Tool {

    public static final String NAME = "run_shell";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Runs a command-line script in the configured shell family "
                    + "and returns its stdout, or the exit code and stderr on failure.",
            schema());

    private final ShellService shell;

    public RunShellTool(ShellService shell) {
        this.shell = shell;
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
        try {
            JsonNode arguments = MAPPER.readTree(call.arguments());
            if (arguments == null || arguments.get("script") == null) {
                return ToolResult.error("run_shell: missing \"script\" argument");
            }
            ShellResult result = shell.run(ShellCommand.of(arguments.get("script").asText()));
            if (result.ok()) {
                return ToolResult.ok(result.stdout().stripTrailing());
            }
            String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
            return ToolResult.error("run_shell exited " + result.exitCode()
                    + ": " + detail.strip());
        } catch (ShellException e) {
            return ToolResult.error("run_shell: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("run_shell: cannot parse arguments: " + e.getMessage());
        }
    }
}
