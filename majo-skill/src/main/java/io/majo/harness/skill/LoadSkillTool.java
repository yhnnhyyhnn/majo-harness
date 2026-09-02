package io.majo.harness.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;

/**
 * Model-facing consumer of the skill registry: loads one skill's instructions
 * by name. Unknown skills surface as an error result, not a crash.
 */
public final class LoadSkillTool implements Tool {

    public static final String NAME = "load_skill";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Loads the full instructions of a named skill (see list_skills for names).",
            schema());

    private final SkillRegistry skills;

    public LoadSkillTool(SkillRegistry skills) {
        this.skills = skills;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("skill").put("type", "string");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("skill");
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
            if (arguments == null || arguments.get("skill") == null) {
                return ToolResult.error("load_skill: missing \"skill\" argument");
            }
            return ToolResult.ok(skills.load(arguments.get("skill").asText()).instructions());
        } catch (SkillException e) {
            return ToolResult.error("load_skill: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("load_skill: cannot parse arguments: " + e.getMessage());
        }
    }
}
