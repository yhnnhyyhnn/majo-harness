package io.majo.harness.skill;

/**
 * A skill failure — the single loud error type of the seam (unknown skill,
 * provider failure, unreadable skill directory).
 */
public final class SkillException extends RuntimeException {

    public SkillException(String message) {
        super(message);
    }

    public SkillException(String message, Throwable cause) {
        super(message, cause);
    }
}
