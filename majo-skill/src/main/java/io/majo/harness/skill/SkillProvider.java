package io.majo.harness.skill;

import java.util.List;

/**
 * The skill provider seam (Service Definition): implementations expose the
 * skills they own. The shipped local provider scans skill directories with
 * {@code SKILL.md} files; remote/curated providers implement the same
 * interface, and name collisions across providers fail loudly on registration.
 */
public interface SkillProvider {

    /** All skills owned by this provider. */
    List<Skill> skills();
}
