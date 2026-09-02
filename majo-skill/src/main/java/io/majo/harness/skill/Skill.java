package io.majo.harness.skill;

/**
 * One skill: a named procedure whose {@code description} feeds the catalog and
 * whose {@code instructions} is the model-visible text loaded on demand.
 */
public record Skill(String name, String description, String instructions) {

    public Skill {
        if (name == null || name.isBlank()) {
            throw new SkillException("skill: name must not be blank");
        }
    }
}
