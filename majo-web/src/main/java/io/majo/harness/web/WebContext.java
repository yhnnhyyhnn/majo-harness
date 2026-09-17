package io.majo.harness.web;

import io.majo.harness.boot.HarnessBoot;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared state every handler group sees: the booted harness, the pending
 * interaction front, per-session turn locks, and process counters.
 */
public final class WebContext {

    public final HarnessBoot boot;
    public final PendingInteractions pending;
    /** Optional shared secret: when set, /api/* requires Bearer or ?token=. */
    public volatile String authToken;
    public final long startedNanos = System.nanoTime();
    public final AtomicLong requestCount = new AtomicLong();
    public final AtomicLong errorCount = new AtomicLong();

    /** Per-session turn locks: independent sessions may run turns in parallel. */
    private final ConcurrentMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public WebContext(HarnessBoot boot, PendingInteractions pending) {
        this.boot = boot;
        this.pending = pending;
    }

    public Object lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, ignored -> new Object());
    }

    /** Drops a session's lock after its last reference is gone (delete). */
    public void removeLock(String sessionId) {
        sessionLocks.remove(sessionId);
    }
}
