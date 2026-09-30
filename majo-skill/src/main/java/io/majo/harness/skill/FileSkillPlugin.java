package io.majo.harness.skill;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The local skill provider: scans configured scope roots (directories of
 * skill folders with {@code SKILL.md}) and registers one provider per scope
 * on {@code ctx.skills} — {@code path} (project, rank 100), {@code custom}
 * (200), {@code user} (300), {@code bundled} (600). Duplicate names resolve
 * by the registry's rank rule (project beats user beats bundled). The
 * disposers are returned so providers unregister when this plugin unloads.
 */
public final class FileSkillPlugin implements Plugin {

    public static final String NAME = "skill-files";

    @Override
    public Object apply(Context ctx, Object config) {
        if (!(config instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("skill-files: config requires scope roots "
                    + "(\"path\" for the project scope, or custom/user/bundled)");
        }
        SkillRegistry skills = ctx.get(SkillRegistry.NAME);
        List<Disposable> registrations = new ArrayList<>();
        registerScope(skills, registrations, map, "path", "project", SkillRegistry.PROJECT_RANK);
        registerScope(skills, registrations, map, "custom", "custom", SkillRegistry.CUSTOM_RANK);
        registerScope(skills, registrations, map, "user", "user", SkillRegistry.USER_RANK);
        registerScope(skills, registrations, map, "bundled", "bundled", SkillRegistry.BUNDLED_RANK);
        if (registrations.isEmpty()) {
            throw new IllegalArgumentException("skill-files: config requires at least one scope root "
                    + "(\"path\", \"custom\", \"user\", or \"bundled\")");
        }
        return io.majo.harness.util.Disposables.composite(registrations);
    }

    private static void registerScope(SkillRegistry skills, List<Disposable> registrations,
            Map<?, ?> map, String key, String source, int rank) {
        if (map.get(key) == null) {
            return;
        }
        registrations.add(skills.register(
                new FileSkillProvider(Path.of(String.valueOf(map.get(key)))), source, rank));
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
