package io.majo.harness.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;

/**
 * Model-facing consumer of the skill catalog: lists every registered skill
 * name and description so the model can pick one to load.
 */
public final class ListSkillsTool implements Tool {

    public static final String NAME = "list_skills";

    private static final ToolSpec SPEC = ToolSpec.of(NAME, "Lists the available skills (name and description).");

    private final SkillRegistry skills;

    public ListSkillsTool(SkillRegistry skills) {
        this.skills = skills;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        StringBuilder catalog = new StringBuilder();
        for (Skill skill : skills.skills()) {
            catalog.append("- ").append(skill.name());
            if (skill.description() != null) {
                catalog.append(": ").append(skill.description());
            }
            catalog.append('\n');
        }
        String text = catalog.toString();
        return ToolResult.ok(text.isEmpty() ? "no skills registered" : text.stripTrailing());
    }
}
