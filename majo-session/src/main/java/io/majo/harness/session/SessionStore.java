package io.majo.harness.session;

import java.util.List;

/**
 * Persistence seam for the session log, mirroring the dsh session store.
 *
 * <p>Implementations must be thread-safe and keep append order stable; the
 * durable store is the source of truth a session is replayed from.
 */
public interface SessionStore {

    /**
     * Creates an empty session, failing loudly when {@code sessionId} exists.
     *
     * @return the created session id
     */
    String createSession(String sessionId);

    /** Appends one event; unknown sessions are rejected loudly. */
    void append(String sessionId, SessionEvent event);

    /** All events of a session in append order (immutable copy). */
    List<SessionEvent> events(String sessionId);

    /** Every session id known to this store. */
    List<String> sessionIds();
}
