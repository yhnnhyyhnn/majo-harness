package io.majo.harness.subprocess;

/**
 * A subprocess failure — the single loud error type of the seam. Providers
 * throw it for start/timed-out failures; policy listeners throw it to reject
 * before execution.
 */
public final class SubprocessException extends RuntimeException {

    public SubprocessException(String message) {
        super(message);
    }

    public SubprocessException(String message, Throwable cause) {
        super(message, cause);
    }
}
