package io.majo.harness.shell;

/**
 * A shell failure — the single loud error type of the seam. Providers throw it
 * for blank scripts and execution-world failures; policy listeners throw it to
 * reject before execution.
 */
public final class ShellException extends RuntimeException {

    public ShellException(String message) {
        super(message);
    }

    public ShellException(String message, Throwable cause) {
        super(message, cause);
    }
}
