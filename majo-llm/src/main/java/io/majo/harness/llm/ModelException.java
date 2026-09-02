package io.majo.harness.llm;

/** Signals a model-adapter failure (transport, protocol, or policy). */
public final class ModelException extends RuntimeException {

    public ModelException(String message) {
        super(message);
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
