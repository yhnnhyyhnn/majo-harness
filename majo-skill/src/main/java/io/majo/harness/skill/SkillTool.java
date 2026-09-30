package io.majo.harness.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;

/**
 * The single model-facing skill tool (dsh tool-skill parity): loads the full
 * instructions for one named skill. The catalog lives in the {@code skills}
 * system section, so this tool takes no "list" mode — names come from
 * {@code <available_skills>}.
 */
public final class SkillTool implements Tool {

    public static final String NAME = "skill";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillRegistry skills;

    public SkillTool(SkillRegistry skills) {
        this.skills = skills;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("skill").put("type", "string")
                .put("description", "The exact skill name from the available skills list.");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("skill");
        return schema;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(NAME,
                "Load the full instructions for a skill. Call it before acting on a task "
                        + "that names or clearly matches a skill in the available skills list "
                        + "(see the <available_skills> catalog).",
                schema());
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode arguments = MAPPER.readTree(call.arguments());
            String name = arguments == null ? "" : arguments.path("skill").asText("");
            if (name.isBlank()) {
                return ToolResult.error("skill: pass the exact skill name from the catalog");
            }
            Skill skill = skills.load(name);
            return ToolResult.ok(skill.instructions() == null ? "" : skill.instructions().stripTrailing(),
                    java.util.Map.of("skill", skill.name(), "source", skill.source()));
        } catch (SkillException e) {
            return ToolResult.error("skill: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("skill: cannot parse arguments: " + e.getMessage());
        }
    }
}
