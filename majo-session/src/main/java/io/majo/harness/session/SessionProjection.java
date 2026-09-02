package io.majo.harness.session;

/**
 * A projection unit registered on {@link SessionProjections}: folds committed
 * events of every session into incremental typed state. Units keep their own
 * per-session state and expose typed reads to host consumers (a unit is read
 * through its concrete type; the registry only feeds and holds it).
 *
 * <p>Units must be safe for sequential folds from any thread — the registry
 * serializes dispatch, but a unit may be replayed and fed live over time.
 */
public interface SessionProjection {

    /** Folds one committed event into this unit's state. */
    void onEvent(String sessionId, TypedSessionEvent event);
}
