package io.majo.harness.skill;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.util.Disposables;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The skill tool consumer: registers the single model-facing {@code skill}
 * tool (dsh tool-skill parity — load-on-demand; the catalog rides the
 * {@code skills} system section as a persistent {@code <available_skills>}
 * block instead of a second tool) on {@code ctx.tools}, once the tools and
 * skills services are live.
 */
public final class SkillToolsPlugin implements Plugin {

    public static final String NAME = "skill-tools";

    @Override
    public Object apply(Context ctx, Object config) {
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        SkillRegistry skills = ctx.get(SkillRegistry.NAME);
        List<Disposable> registrations = new ArrayList<>();
        registrations.add(tools.register(new SkillTool(skills)));
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        if (loop != null) {
            // profiles without the loop (headless tool lists) skip the catalog
            registrations.add(loop.registerSystemSection("skills", () -> catalog(skills)));
        }
        return Disposables.composite(registrations);
    }

    /**
     * The {@code <available_skills>} catalog block (dsh framing): summaries
     * only, with the load-before-acting instruction; {@code null} when no
     * skills are registered (the section is skipped).
     */
    static String catalog(SkillRegistry skills) {
        List<Skill> catalog = skills.skills();
        if (catalog.isEmpty()) {
            return null;
        }
        StringBuilder block = new StringBuilder("<available_skills>\n");
        for (Skill skill : catalog) {
            block.append("- ").append(skill.name());
            if (skill.description() != null && !skill.description().isBlank()) {
                block.append(": ").append(skill.description());
            }
            block.append('\n');
        }
        block.append("</available_skills>\n\n")
                .append("If the user names a skill, or the task clearly matches a skill's "
                        + "description, call the `skill` tool with the exact skill name before "
                        + "taking task actions. This catalog contains summaries only; do not "
                        + "follow a skill's instructions until it has been loaded.");
        return block.toString();
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(ToolRegistry.NAME, null);
        inject.put(SkillRegistry.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
