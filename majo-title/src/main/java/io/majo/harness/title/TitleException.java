package io.majo.harness.title;

/**
 * A session-title failure — the single loud error type of the seam (no title
 * provider registered).
 */
public final class TitleException extends RuntimeException {

    public TitleException(String message) {
        super(message);
    }

    public TitleException(String message, Throwable cause) {
        super(message, cause);
    }
}
