package io.majo.harness.skill;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The skill registry ({@code ctx.skills}): aggregates registered
 * {@link SkillProvider providers}; consumers browse the catalog
 * ({@link #skills}) and load one skill by name ({@link #load}, failing loudly
 * on unknowns). Provider names are checked for collisions at registration so a
 * duplicate never silently shadows an earlier skill.
 */
public final class SkillRegistry extends Service {

    public static final String NAME = "skills";

    private final Map<SkillProvider, Boolean> providers = new ConcurrentHashMap<>();

    public SkillRegistry(Context ctx) {
        super(ctx, NAME);
    }

    /** Registers a provider, failing loudly on skill-name collisions. */
    public Disposable register(SkillProvider provider) {
        Set<String> names = new HashSet<>();
        for (Skill skill : provider.skills()) {
            if (!names.add(skill.name())) {
                throw new SkillException("provider exposes duplicate skill \"" + skill.name() + "\"");
            }
            if (allNames().contains(skill.name())) {
                throw new SkillException("skill \"" + skill.name() + "\" has been registered by another provider");
            }
        }
        providers.put(provider, Boolean.TRUE);
        return () -> providers.remove(provider);
    }

    /** The aggregated catalog across every provider (deterministic order). */
    public List<Skill> skills() {
        List<Skill> skills = new ArrayList<>();
        for (SkillProvider provider : providers.keySet()) {
            skills.addAll(provider.skills());
        }
        skills.sort(java.util.Comparator.comparing(Skill::name));
        return List.copyOf(skills);
    }

    /** Loads one skill by name, failing loudly with the available catalog. */
    public Skill load(String name) {
        for (Skill skill : skills()) {
            if (skill.name().equals(name)) {
                return skill;
            }
        }
        throw new SkillException("unknown skill \"" + name + "\"; available: "
                + skills().stream().map(Skill::name).toList());
    }

    /** Whether a provider is registered. */
    public boolean hasProviders() {
        return !providers.isEmpty();
    }

    private Set<String> allNames() {
        Set<String> names = new HashSet<>();
        for (Skill skill : skills()) {
            names.add(skill.name());
        }
        return names;
    }
}
