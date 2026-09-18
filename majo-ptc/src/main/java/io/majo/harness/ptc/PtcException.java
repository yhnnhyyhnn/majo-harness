package io.majo.harness.ptc;

/** Signals a PTC execution failure (spawn, timeout, or non-zero exit). */
public final class PtcException extends RuntimeException {

    public PtcException(String message) {
        super(message);
    }

    public PtcException(String message, Throwable cause) {
        super(message, cause);
    }
}
