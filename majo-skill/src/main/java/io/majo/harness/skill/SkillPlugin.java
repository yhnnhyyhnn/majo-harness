package io.majo.harness.skill;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/** Mounts {@link SkillRegistry} as the {@code skills} plugin. */
public final class SkillPlugin implements Plugin {

    public static final String NAME = "skills";

    @Override
    public Object apply(Context ctx, Object config) {
        new SkillRegistry(ctx);
        return null;
    }

    @Override
    public String name() {
        return NAME;
    }
}
