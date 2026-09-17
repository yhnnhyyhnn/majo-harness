package io.majo.harness.web.handler;

import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session lowercase search entries, cached against the session's event
 * count as the version — the dashboard's debounced search no longer rescans
 * (and re-lowercases) the whole log on every keystroke. Bounded: when more
 * than {@link #MAX_SESSIONS} sessions are cached the whole cache resets
 * (single-user scope; correctness never depends on the cache).
 */
public final class SearchIndex {

    private static final int MAX_SESSIONS = 64;

    /** One searchable event: durable seq plus original and lowercased text. */
    public record Entry(long seq, String lower, String display) {
    }

    private record Cached(long version, List<Entry> entries) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SearchIndex() {
    }

    /** The searchable entries for the session, rebuilt when the log grew. */
    public List<Entry> entries(SessionService sessions, String sessionId) {
        List<SessionEvent> events = sessions.events(sessionId);
        Cached cached = cache.get(sessionId);
        if (cached != null && cached.version() == events.size()) {
            return cached.entries();
        }
        if (cache.size() >= MAX_SESSIONS) {
            cache.clear();
        }
        List<Entry> entries = new ArrayList<>(events.size());
        for (SessionEvent event : events) {
            String content = event.content();
            if (content == null || content.isBlank()) {
                continue;
            }
            entries.add(new Entry(event.seq(), content.toLowerCase(Locale.ROOT), content));
        }
        Cached fresh = new Cached(events.size(), List.copyOf(entries));
        cache.put(sessionId, fresh);
        return fresh.entries();
    }

    /** Drops a deleted session's entries so ids can never collide stale. */
    public void drop(String sessionId) {
        cache.remove(sessionId);
    }
}
