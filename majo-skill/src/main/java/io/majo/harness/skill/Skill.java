package io.majo.harness.skill;

/**
 * One skill: a named procedure whose {@code description} feeds the catalog and
 * whose {@code instructions} is the model-visible text loaded on demand.
 * {@code source} is the origin bucket ({@code project}/{@code custom}/
 * {@code user}/{@code bundled}) — prompt-visible metadata, not precedence by
 * itself (the registry's rank decides precedence, dsh parity).
 */
public record Skill(String name, String description, String instructions, String source) {

    public Skill {
        if (name == null || name.isBlank()) {
            throw new SkillException("skill: name must not be blank");
        }
        if (source == null || source.isBlank()) {
            source = "custom";
        }
    }

    public Skill(String name, String description, String instructions) {
        this(name, description, instructions, "custom");
    }

    /** A copy of this skill under a different source label (registry tagging). */
    public Skill withSource(String newSource) {
        return new Skill(name, description, instructions, newSource);
    }
}
