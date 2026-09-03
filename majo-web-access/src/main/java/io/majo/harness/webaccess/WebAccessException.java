package io.majo.harness.webaccess;

/**
 * A web access failure — the single loud error type of the seam: no provider
 * mounted, provider errors, non-2xx fetches, oversized responses.
 */
public final class WebAccessException extends RuntimeException {

    public WebAccessException(String message) {
        super(message);
    }

    public WebAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
