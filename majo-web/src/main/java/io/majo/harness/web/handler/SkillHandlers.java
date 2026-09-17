package io.majo.harness.web.handler;

import io.majo.harness.skill.Skill;
import io.majo.harness.skill.SkillRegistry;
import io.majo.harness.web.WebApiModels;
import io.majo.harness.web.WebContext;
import java.util.List;

/** Skill catalog browsing. */
public final class SkillHandlers {

    private final WebContext ctx;

    public SkillHandlers(WebContext ctx) {
        this.ctx = ctx;
    }

    public WebApiModels.SkillsIndex index() {
        SkillRegistry skills = ctx.boot.ctx().get(SkillRegistry.NAME);
        if (skills == null) {
            return new WebApiModels.SkillsIndex(List.of());
        }
        return new WebApiModels.SkillsIndex(skills.skills().stream()
                .map(skill -> new WebApiModels.SkillInfo(skill.name(), skill.description()))
                .toList());
    }

    public WebApiModels.SkillDetail detail(String name) {
        SkillRegistry skills = ctx.boot.ctx().get(SkillRegistry.NAME);
        if (skills == null) {
            throw new IllegalArgumentException("skills service unavailable — mount the skills row");
        }
        Skill skill = skills.skills().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown skill \"" + name + "\""));
        return new WebApiModels.SkillDetail(skill.name(), skill.description(), skill.instructions());
    }
}
