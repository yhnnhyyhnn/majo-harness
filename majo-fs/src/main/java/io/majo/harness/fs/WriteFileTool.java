package io.majo.harness.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Model-facing consumer of the fs seam: creates or overwrites a UTF-8 text
 * file (parent directories are created automatically). For modifying an
 * existing file, {@link EditFileTool} is preferred — it fails loudly on
 * unexpected content instead of clobbering the whole file.
 */
public final class WriteFileTool implements Tool {

    public static final String NAME = "write_file";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Creates or overwrites a UTF-8 text file at the given path "
                    + "(parent directories are created automatically). "
                    + "To change an existing file prefer edit_file.",
            schema());

    private final FileSystemService fs;

    public WriteFileTool(FileSystemService fs) {
        this.fs = fs;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("path").put("type", "string");
        properties.putObject("content").put("type", "string")
                .put("description", "The complete new file content (empty string allowed).");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("path").add("content");
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
                return ToolResult.error("write_file: missing \"path\" argument");
            }
            if (arguments.get("content") == null) {
                return ToolResult.error("write_file: missing \"content\" argument");
            }
            String path = arguments.get("path").asText();
            String content = arguments.get("content").asText("");
            fs.writeText(path, content);
            return ToolResult.ok("wrote " + content.getBytes(StandardCharsets.UTF_8).length
                    + " bytes to " + path, Map.of("path", path, "bytes", content.length()));
        } catch (FsException e) {
            return ToolResult.error("write_file: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("write_file: cannot parse arguments: " + e.getMessage());
        }
    }
}
