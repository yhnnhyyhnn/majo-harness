package io.majo.harness.web;

import java.util.Map;

/** Per-request fine metrics for /api/metrics (static: one server per JVM). */
public final class Metrics {
    private static final java.util.concurrent.atomic.AtomicLong CLIENT_ABORTS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong TURNS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong APPROVALS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong QUESTIONS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong RELOADS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final long[] BUCKET_LIMITS_MS = {5, 20, 100, 500, 2_000};
    private static final String[] BUCKET_NAMES = {"under5ms", "under20ms", "under100ms",
            "under500ms", "under2000ms", "over2000ms"};
    private static final java.util.concurrent.atomic.AtomicLong[] LATENCY =
            new java.util.concurrent.atomic.AtomicLong[BUCKET_NAMES.length];
    private static final java.util.concurrent.atomic.AtomicLong[] STATUS =
            new java.util.concurrent.atomic.AtomicLong[6];
    private static final ThreadLocal<Long> STARTED = new ThreadLocal<>();

    static {
        for (int i = 0; i < LATENCY.length; i++) {
            LATENCY[i] = new java.util.concurrent.atomic.AtomicLong();
        }
        for (int i = 0; i < STATUS.length; i++) {
            STATUS[i] = new java.util.concurrent.atomic.AtomicLong();
        }
    }

    public static void begin() {
        STARTED.set(System.nanoTime());
    }

    /** Records one finished request; clears the per-request timer. */
    public static void record(int status) {
        Long started = STARTED.get();
        if (started == null) {
            return;
        }
        STARTED.remove();
        long ms = (System.nanoTime() - started) / 1_000_000;
        int bucket = BUCKET_LIMITS_MS.length;
        for (int i = 0; i < BUCKET_LIMITS_MS.length; i++) {
            if (ms < BUCKET_LIMITS_MS[i]) {
                bucket = i;
                break;
            }
        }
        LATENCY[bucket].incrementAndGet();
        if (status >= 100 && status < 600) {
            STATUS[status / 100].incrementAndGet();
        }
    }

    public static void abort() {
        CLIENT_ABORTS.incrementAndGet();
        STARTED.remove();
    }

    public static void turn() {
        TURNS.incrementAndGet();
    }

    public static void approvalDecided() {
        APPROVALS.incrementAndGet();
    }

    public static void questionAnswered() {
        QUESTIONS.incrementAndGet();
    }

    public static void reloaded() {
        RELOADS.incrementAndGet();
    }

    public static Map<String, Object> snapshot(long startedNanos, long requests, long errors) {
        Map<String, Long> latency = new java.util.LinkedHashMap<>();
        for (int i = 0; i < LATENCY.length; i++) {
            latency.put(BUCKET_NAMES[i], LATENCY[i].get());
        }
        Map<String, Long> status = new java.util.LinkedHashMap<>();
        String[] labels = {"1xx", "2xx", "3xx", "4xx", "5xx"};
        for (int i = 1; i <= 5; i++) {
            status.put(labels[i - 1], STATUS[i].get());
        }
        status.put("aborted", CLIENT_ABORTS.get());
        Map<String, Object> all = new java.util.LinkedHashMap<>();
        all.put("uptimeMs", (System.nanoTime() - startedNanos) / 1_000_000);
        all.put("requests", requests);
        all.put("errors", errors);
        all.put("turns", TURNS.get());
        all.put("approvalsDecided", APPROVALS.get());
        all.put("questionsAnswered", QUESTIONS.get());
        all.put("pluginsReloaded", RELOADS.get());
        all.put("status", status);
        all.put("latencyMs", latency);
        return all;
    }
}
