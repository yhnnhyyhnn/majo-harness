package io.majo.harness.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory {@link SessionStore}: the default for one-shot runs. */
public final class InMemorySessionStore implements SessionStore {

    private final Map<String, List<SessionEvent>> sessions = new LinkedHashMap<>();

    @Override
    public synchronized String createSession(String sessionId) {
        if (sessions.containsKey(sessionId)) {
            throw new IllegalArgumentException("session \"" + sessionId + "\" already exists");
        }
        sessions.put(sessionId, new ArrayList<>());
        return sessionId;
    }

    @Override
    public synchronized void append(String sessionId, SessionEvent event) {
        List<SessionEvent> events = sessions.get(sessionId);
        if (events == null) {
            throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
        }
        events.add(event);
    }

    @Override
    public synchronized List<SessionEvent> events(String sessionId) {
        List<SessionEvent> events = sessions.get(sessionId);
        if (events == null) {
            throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
        }
        return List.copyOf(events);
    }

    @Override
    public synchronized List<String> sessionIds() {
        return List.copyOf(sessions.keySet());
    }

    @Override
    public synchronized void remove(String sessionId) {
        if (sessions.remove(sessionId) == null) {
            throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
        }
    }
}
