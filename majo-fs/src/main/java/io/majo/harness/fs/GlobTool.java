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
 * Model-facing consumer of the fs seam: recursive file search by glob
 * pattern under a root directory ({@code *} stays inside one path segment,
 * {@code **} crosses directories).
 */
public final class GlobTool implements Tool {

    public static final String NAME = "glob";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Finds files under a directory by glob pattern (recursive). "
                    + "\"*\" matches within one path segment, \"**\" crosses "
                    + "directories — e.g. \"**/*.java\", \"src/*.txt\".",
            schema());

    private static final int MAX_LISTED = 500;

    private final FileSystemService fs;

    public GlobTool(FileSystemService fs) {
        this.fs = fs;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("root").put("type", "string")
                .put("description", "Directory to search under (absolute).");
        properties.putObject("pattern").put("type", "string")
                .put("description", "Glob pattern, matched against paths relative to root.");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("root").add("pattern");
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
            if (arguments == null || arguments.get("root") == null
                    || arguments.get("pattern") == null) {
                return ToolResult.error("glob: \"root\" and \"pattern\" are required");
            }
            String root = arguments.get("root").asText();
            String pattern = arguments.get("pattern").asText();
            List<String> matches = fs.glob(root, pattern);
            if (matches.isEmpty()) {
                return ToolResult.ok("no files match " + pattern + " under " + root,
                        Map.of("count", 0));
            }
            String notice = matches.size() > MAX_LISTED
                    ? "\n… (" + (matches.size() - MAX_LISTED) + " more; refine the pattern)"
                    : "";
            String listed = String.join("\n",
                    matches.subList(0, Math.min(MAX_LISTED, matches.size())));
            return ToolResult.ok(listed + notice,
                    Map.of("count", matches.size()));
        } catch (FsException e) {
            return ToolResult.error("glob: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("glob: cannot parse arguments: " + e.getMessage());
        }
    }
}
