package io.majo.harness.subagent;

/**
 * A subagent failure — the single loud error type of the seam (for example a
 * delegation exceeding the configured nesting depth).
 */
public final class SubagentException extends RuntimeException {

    public SubagentException(String message) {
        super(message);
    }

    public SubagentException(String message, Throwable cause) {
        super(message, cause);
    }
}
