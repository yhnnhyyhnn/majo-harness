package io.majo.harness.interaction;

/**
 * An interaction failure — the single loud error type of the seam (for
 * example when a question reaches no handler).
 */
public final class InteractionException extends RuntimeException {

    public InteractionException(String message) {
        super(message);
    }

    public InteractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
