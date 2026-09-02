package io.majo.harness.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;

/**
 * Model-facing consumer of the fs seam: reads a text file by absolute path.
 * Provider and policy failures surface to the model as an error result rather
 * than crashing the turn.
 */
public final class ReadFileTool implements Tool {

    public static final String NAME = "read_file";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Reads the UTF-8 text content of a file at an absolute path.",
            schema());

    private final FileSystemService fs;

    public ReadFileTool(FileSystemService fs) {
        this.fs = fs;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("path").put("type", "string");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("path");
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
            if (arguments == null || arguments.get("path") == null) {
                return ToolResult.error("read_file: missing \"path\" argument");
            }
            String content = fs.readText(arguments.get("path").asText());
            return ToolResult.ok(content);
        } catch (FsException e) {
            return ToolResult.error("read_file: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("read_file: cannot parse arguments: " + e.getMessage());
        }
    }
}
