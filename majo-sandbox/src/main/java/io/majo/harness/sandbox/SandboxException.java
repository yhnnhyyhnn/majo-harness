package io.majo.harness.sandbox;

/**
 * A confinement failure — the single loud error type of the seam. Providers
 * throw it when they cannot confine; policy listeners throw it to reject
 * before confinement.
 */
public final class SandboxException extends RuntimeException {

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
