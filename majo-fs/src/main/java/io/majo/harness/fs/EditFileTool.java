package io.majo.harness.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.util.Map;

/**
 * Model-facing consumer of the fs seam: exact-string replacement inside an
 * existing text file. The old text must appear exactly once unless
 * {@code replace_all} is set — a deliberate single-edit contract that turns
 * "am I touching the right place?" into a loud failure instead of silent
 * clobbering (dsh str_replace_editor semantics, composed on read+write so
 * remote providers get it for free).
 */
public final class EditFileTool implements Tool {

    public static final String NAME = "edit_file";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Replaces an exact string inside an existing UTF-8 text file. "
                    + "old_string must appear exactly once unless replace_all is true "
                    + "(then every occurrence is replaced). Include enough surrounding "
                    + "context to make old_string unique.",
            schema());

    private final FileSystemService fs;

    public EditFileTool(FileSystemService fs) {
        this.fs = fs;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("path").put("type", "string");
        properties.putObject("old_string").put("type", "string")
                .put("description", "The exact text to replace (must match verbatim).");
        properties.putObject("new_string").put("type", "string")
                .put("description", "The replacement text (empty string deletes).");
        properties.putObject("replace_all").put("type", "boolean")
                .put("description", "Replace every occurrence (default: false, require exactly one).");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("path").add("old_string").add("new_string");
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
                return ToolResult.error("edit_file: missing \"path\" argument");
            }
            JsonNode oldNode = arguments.get("old_string");
            JsonNode newNode = arguments.get("new_string");
            if (oldNode == null || newNode == null) {
                return ToolResult.error("edit_file: \"old_string\" and \"new_string\" are required");
            }
            String path = arguments.get("path").asText();
            String oldText = oldNode.asText();
            String newText = newNode.asText();
            boolean replaceAll = arguments.path("replace_all").asBoolean(false);

            String content;
            try {
                content = fs.readText(path);
            } catch (FsException e) {
                return ToolResult.error("edit_file: cannot read " + path + ": " + e.getMessage());
            }
            if (oldText.isEmpty()) {
                return ToolResult.error("edit_file: \"old_string\" must not be empty");
            }
            int first = content.indexOf(oldText);
            if (first < 0) {
                return ToolResult.error("edit_file: old_string not found in " + path
                        + " — read the file and copy the text verbatim");
            }
            int second = content.indexOf(oldText, first + 1);
            if (second >= 0 && !replaceAll) {
                int occurrences = 2;
                int scan = content.indexOf(oldText, second + 1);
                while (scan >= 0) {
                    occurrences++;
                    scan = content.indexOf(oldText, scan + 1);
                }
                return ToolResult.error("edit_file: old_string appears " + occurrences
                        + " times in " + path + " — add surrounding context to make it "
                        + "unique, or pass replace_all=true");
            }
            String updated = replaceAll
                    ? content.replace(oldText, newText)
                    : content.substring(0, first) + newText + content.substring(first + oldText.length());
            fs.writeText(path, updated);
            int replaced = replaceAll ? Math.max(1, occurrences(content, oldText)) : 1;
            return ToolResult.ok("edited " + path + " (" + replaced
                    + (replaced == 1 ? " occurrence" : " occurrences") + " replaced)",
                    Map.of("path", path, "replacements", replaced));
        } catch (FsException e) {
            return ToolResult.error("edit_file: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("edit_file: cannot parse arguments: " + e.getMessage());
        }
    }

    private static int occurrences(String content, String text) {
        int count = 0;
        int index = content.indexOf(text);
        while (index >= 0) {
            count++;
            index = content.indexOf(text, index + 1);
        }
        return count;
    }
}
