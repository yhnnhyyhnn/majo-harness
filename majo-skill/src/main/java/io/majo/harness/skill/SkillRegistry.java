package io.majo.harness.skill;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The skill registry ({@code ctx.skills}, dsh scope-chain parity): aggregates
 * registered {@link SkillProvider providers} under precedence ranks — for a
 * duplicated skill name the <em>lowest</em> rank wins outright, first
 * registration breaks rank ties, and the loser is logged and skipped (its
 * provider's other skills still work). Within one provider a duplicate name
 * still fails loudly (a provider contradicting itself is a bug). Standard
 * ranks: {@link #PROJECT_RANK} &lt; {@link #CUSTOM_RANK} &lt;
 * {@link #USER_RANK} &lt; {@link #BUNDLED_RANK}.
 */
public final class SkillRegistry extends Service {

    public static final String NAME = "skills";
    static final Logger LOG = LoggerFactory.getLogger(SkillRegistry.class);

    /** Project-scope rank (repo skills — highest priority). */
    public static final int PROJECT_RANK = 100;
    /** Custom-scope rank (explicitly configured extra roots). */
    public static final int CUSTOM_RANK = 200;
    /** User-scope rank (per-home skills). */
    public static final int USER_RANK = 300;
    /** Bundled-scope rank (dsh BUNDLED_SKILL_RANK parity — lowest priority). */
    public static final int BUNDLED_RANK = 600;

    /** One registration: the provider plus its scope metadata and arrival order. */
    private record Entry(SkillProvider provider, String source, int rank, int order) {}

    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();
    private final AtomicInteger order = new AtomicInteger();

    public SkillRegistry(Context ctx) {
        super(ctx, NAME);
    }

    /** Registers a provider at the default custom scope. */
    public Disposable register(SkillProvider provider) {
        return register(provider, "custom", CUSTOM_RANK);
    }

    /**
     * Registers a provider under a scope label and rank (lower wins duplicate
     * names). Collisions with an existing higher-priority skill are logged and
     * skipped; self-contradicting providers fail loudly.
     */
    public Disposable register(SkillProvider provider, String source, int rank) {
        Set<String> own = new HashSet<>();
        for (Skill skill : provider.skills()) {
            if (!own.add(skill.name())) {
                throw new SkillException("provider exposes duplicate skill \"" + skill.name() + "\"");
            }
        }
        Entry entry = new Entry(provider, source, rank, order.getAndIncrement());
        for (Skill skill : provider.skills()) {
            Skill winner = currentWinner(skill.name());
            if (winner != null && rank > rankOf(skill.name())) {
                LOG.warn("skill \"{}\" from {} ignored because a higher-priority skill already exists",
                        skill.name(), source);
            }
        }
        entries.add(entry);
        return () -> entries.remove(entry);
    }

    /** The winning catalog across every provider (deterministic name order). */
    public List<Skill> skills() {
        Map<String, Skill> winners = new HashMap<>();
        List<Skill> skills = new ArrayList<>();
        for (Skill skill : winnersByName().values()) {
            skills.add(skill);
        }
        skills.sort(java.util.Comparator.comparing(Skill::name));
        return List.copyOf(skills);
    }

    /** Loads one skill by name (the rank winner), failing loudly with the catalog. */
    public Skill load(String name) {
        Skill skill = winnersByName().get(name);
        if (skill == null) {
            throw new SkillException("unknown skill \"" + name + "\"; available: "
                    + skills().stream().map(Skill::name).toList());
        }
        return skill;
    }

    /** Whether a provider is registered. */
    public boolean hasProviders() {
        return !entries.isEmpty();
    }

    /** Resolves the winning skill per name: min rank, then earliest arrival. */
    private Map<String, Skill> winnersByName() {
        Map<String, Skill> winners = new HashMap<>();
        Map<String, Entry> winnerEntries = new HashMap<>();
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(java.util.Comparator.comparingInt(Entry::order));
        for (Entry entry : sorted) {
            for (Skill skill : entry.provider().skills()) {
                Skill tagged = skill.withSource(entry.source());
                Entry incumbent = winnerEntries.get(skill.name());
                if (incumbent == null || entry.rank() < incumbent.rank()) {
                    winners.put(skill.name(), tagged);
                    winnerEntries.put(skill.name(), entry);
                }
            }
        }
        return winners;
    }

    private Skill currentWinner(String name) {
        return winnersByName().get(name);
    }

    private int rankOf(String name) {
        for (Entry entry : entries) {
            for (Skill skill : entry.provider().skills()) {
                if (skill.name().equals(name)) {
                    return entry.rank();
                }
            }
        }
        return Integer.MAX_VALUE;
    }
}
