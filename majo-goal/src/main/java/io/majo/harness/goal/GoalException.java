package io.majo.harness.goal;

/** Signals a goal-domain failure (CAS mismatch, illegal transition, authority). */
public final class GoalException extends RuntimeException {

    public GoalException(String message) {
        super(message);
    }
}
