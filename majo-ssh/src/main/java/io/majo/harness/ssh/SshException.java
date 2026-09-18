package io.majo.harness.ssh;

/** Signals an SSH transport or remote-execution failure. */
public final class SshException extends RuntimeException {

    public SshException(String message) {
        super(message);
    }

    public SshException(String message, Throwable cause) {
        super(message, cause);
    }
}
