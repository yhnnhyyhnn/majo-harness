package io.majo.harness.skill;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The local skill provider: scans {@code config.path} (a directory of skill
 * folders with {@code SKILL.md}) and registers the provider on
 * {@code ctx.skills}. The disposer is returned so the provider unregisters
 * when this plugin unloads.
 */
public final class FileSkillPlugin implements Plugin {

    public static final String NAME = "skill-files";

    @Override
    public Object apply(Context ctx, Object config) {
        if (!(config instanceof Map<?, ?> map) || map.get("path") == null) {
            throw new IllegalArgumentException("skill-files: config requires a \"path\" directory");
        }
        SkillRegistry skills = ctx.get(SkillRegistry.NAME);
        Disposable registration = skills.register(new FileSkillProvider(Path.of(String.valueOf(map.get("path")))));
        return registration;
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(SkillRegistry.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
