package io.majo.harness.credentials;

/**
 * A credential failure — the single loud error type of the seam. Messages
 * never include provider values (secrets stay out of logs).
 */
public final class CredentialException extends RuntimeException {

    public CredentialException(String message) {
        super(message);
    }

    public CredentialException(String message, Throwable cause) {
        super(message, cause);
    }
}
