package io.majo.harness.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.util.List;
import java.util.Map;

/**
 * Model-facing consumer of the fs seam: regex content search under a path
 * (file or directory, recursed), with an optional file-name glob filter and
 * a head limit. Output lines are {@code path:line:text}.
 */
public final class GrepTool implements Tool {

    public static final String NAME = "grep";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Searches file content for a regular expression under a path "
                    + "(a file or a directory, recursed; binary files skipped). "
                    + "Returns \"path:line:text\" hits. Optional include filter "
                    + "restricts searched files by name glob.",
            schema());

    private static final int DEFAULT_HEAD = 100;
    private static final int MAX_HEAD = 1000;

    private final FileSystemService fs;

    public GrepTool(FileSystemService fs) {
        this.fs = fs;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("path").put("type", "string")
                .put("description", "File or directory to search (absolute).");
        properties.putObject("pattern").put("type", "string")
                .put("description", "Regular expression (Java syntax).");
        properties.putObject("include").put("type", "string")
                .put("description", "Optional file-name glob filter, e.g. \"*.java\".");
        properties.putObject("head_limit").put("type", "integer")
                .put("description", "Maximum hits returned (default 100, max 1000).");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("path").add("pattern");
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
            if (arguments == null || arguments.get("path") == null
                    || arguments.get("pattern") == null) {
                return ToolResult.error("grep: \"path\" and \"pattern\" are required");
            }
            String path = arguments.get("path").asText();
            String pattern = arguments.get("pattern").asText();
            String include = arguments.get("include") == null
                    || arguments.get("include").isNull()
                    ? null : arguments.get("include").asText();
            int headLimit = arguments.path("head_limit").asInt(DEFAULT_HEAD);
            if (headLimit < 1) {
                headLimit = DEFAULT_HEAD;
            }
            headLimit = Math.min(headLimit, MAX_HEAD);

            List<String> hits = fs.grep(path, pattern, include, headLimit);
            if (hits.isEmpty()) {
                return ToolResult.ok("no matches for /" + pattern + "/ under " + path,
                        Map.of("matches", 0));
            }
            boolean truncated = hits.size() >= headLimit;
            String text = String.join("\n", hits)
                    + (truncated ? "\n… (hit limit " + headLimit + "; narrow the pattern "
                            + "or raise head_limit)" : "");
            return ToolResult.ok(text, Map.of("matches", hits.size()));
        } catch (FsException e) {
            return ToolResult.error("grep: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("grep: cannot parse arguments: " + e.getMessage());
        }
    }
}
