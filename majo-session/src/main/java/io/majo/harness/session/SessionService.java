package io.majo.harness.session;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The session-log service ({@code ctx.sessions}): creates sessions, appends
 * durable {@link SessionEvent events}, and broadcasts every append through the
 * {@link #EVENT} event for live observers.
 *
 * <p>The service is itself provided by a plugin, so removing that plugin
 * reverts the service and every consumer that declared it as an injection.
 */
public final class SessionService extends Service {

    /** ctx service key under which this service is registered. */
    public static final String NAME = "sessions";
    /** Live broadcast event fired with {@code (String sessionId, SessionEvent event)}. */
    public static final String EVENT = "session/event";

    private final SessionStore store;

    public SessionService(Context ctx, SessionStore store) {
        super(ctx, NAME);
        this.store = store;
    }

    /** Creates a session with a fresh id and returns it. */
    public String createSession() {
        return store.createSession(UUID.randomUUID().toString());
    }

    /**
     * Appends a durable event to {@code sessionId}, assigns its sequence
     * number and timestamp, and broadcasts {@link #EVENT} with the session id
     * so observers (projections, replay) know which session the event belongs
     * to.
     */
    public SessionEvent append(String sessionId, SessionEventType type, Map<String, Object> fields) {
        long seq = store.events(sessionId).size() + 1;
        SessionEvent event = new SessionEvent(seq, type, System.currentTimeMillis(), fields);
        store.append(sessionId, event);
        ctx.events().emit((Object) null, EVENT, sessionId, event);
        return event;
    }

    /** All events of a session in append order. */
    public List<SessionEvent> events(String sessionId) {
        return store.events(sessionId);
    }

    /** Every session id known to this service's store. */
    public List<String> sessionIds() {
        return store.sessionIds();
    }

    /** Removes a session and its durable log (unknown ids fail loudly). */
    public void remove(String sessionId) {
        store.remove(sessionId);
    }
}
