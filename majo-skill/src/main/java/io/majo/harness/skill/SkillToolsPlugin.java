package io.majo.harness.skill;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.util.Disposables;
import java.util.HashMap;
import java.util.Map;

/**
 * The skill tool consumers: registers {@code list_skills} and {@code load_skill}
 * on {@code ctx.tools} once the tools and skills services are live (further
 * skills compose beside them via {@link Disposables}).
 */
public final class SkillToolsPlugin implements Plugin {

    public static final String NAME = "skill-tools";

    @Override
    public Object apply(Context ctx, Object config) {
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        SkillRegistry skills = ctx.get(SkillRegistry.NAME);
        Disposable list = tools.register(new ListSkillsTool(skills));
        Disposable load = tools.register(new LoadSkillTool(skills));
        return Disposables.composite(list, load);
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
