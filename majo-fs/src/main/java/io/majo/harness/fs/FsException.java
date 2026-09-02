package io.majo.harness.fs;

/**
 * A filesystem operation failure — the single loud error type of the fs seam.
 * Providers throw it for I/O failures; policy listeners throw it to reject an
 * operation (the rejection then surfaces to the caller identically).
 */
public final class FsException extends RuntimeException {

    public FsException(String message) {
        super(message);
    }

    public FsException(String message, Throwable cause) {
        super(message, cause);
    }
}
