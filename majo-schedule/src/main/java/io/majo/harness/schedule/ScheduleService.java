package io.majo.harness.schedule;

import io.majo.harness.agent.loop.AgentLoopService;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The schedule runtime ({@code ctx.schedule}, dsh schedule): per-session
 * reminders recorded as durable {@code SCHEDULE_SET} events and delivered as
 * follow-up turns — an idle harness wakes, a busy one gets the prompt next
 * turn. Timers are in-memory and re-armed from the session logs on mount, so
 * schedules survive a restart; repeating schedules advance in memory only
 * (the log keeps the creation record, like dsh).
 *
 * <p>Shapes: {@code after_seconds} one-shot delay, {@code every_seconds}
 * fixed repeat ({@code >= 300}), or an ISO local {@code at}. No cron, no
 * fork inheritance — matching the reference scope.
 */
public final class ScheduleService extends io.jcordis.core.service.Service {

    public static final String NAME = "schedule";
    public static final long MIN_REPEAT_SECONDS = 300;
    static final Logger LOG = LoggerFactory.getLogger(ScheduleService.class);

    /** One schedule (the read model). */
    public static final class Schedule {
        public final String id;
        public final String prompt;
        public volatile long dueAtMs;
        public final long intervalSeconds;
        public volatile boolean cancelled;

        Schedule(String id, String prompt, long dueAtMs, long intervalSeconds) {
            this.id = id;
            this.prompt = prompt;
            this.dueAtMs = dueAtMs;
            this.intervalSeconds = intervalSeconds;
        }
    }

    private final SessionService sessions;
    private final AgentLoopService loop;
    private final Map<String, Map<String, Schedule>> bySession = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> armed = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "majo-schedule");
                thread.setDaemon(true);
                return thread;
            });

    public ScheduleService(io.jcordis.core.context.Context ctx, SessionService sessions,
            AgentLoopService loop) {
        super(ctx, NAME);
        this.sessions = sessions;
        this.loop = loop;
        rescan();
    }

    /** Rebuilds in-memory schedules from every session's durable log. */
    final void rescan() {
        // a fresh runtime starts empty (restart simulation) and folds the logs
        for (ScheduledFuture<?> future : armed.values()) {
            future.cancel(false);
        }
        armed.clear();
        bySession.clear();
        counters.clear();
        for (String sessionId : sessions.sessionIds()) {
            for (SessionEvent event : sessions.events(sessionId)) {
                if (event.type() != SessionEventType.SCHEDULE_SET) {
                    continue;
                }
                Map<String, Object> fields = event.fields();
                apply(sessionId, new Schedule(
                        String.valueOf(fields.get(SessionEvent.FIELD_SCHEDULE_ID)),
                        String.valueOf(fields.get(SessionEvent.FIELD_PROMPT)),
                        Long.parseLong(String.valueOf(fields.get(SessionEvent.FIELD_DUE_AT))),
                        Long.parseLong(String.valueOf(fields.get(SessionEvent.FIELD_INTERVAL_SECONDS)))),
                        Boolean.parseBoolean(String.valueOf(fields.get(SessionEvent.FIELD_CANCELLED))));
            }
        }
    }

    private void apply(String sessionId, Schedule schedule, boolean cancelled) {
        Map<String, Schedule> schedules = bySession.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>());
        Schedule previous = schedules.get(schedule.id);
        if (previous != null) {
            cancelTimer(sessionId, schedule.id);
        }
        schedules.put(schedule.id, schedule);
        if (cancelled) {
            schedule.cancelled = true;
            return;
        }
        int counter = 0;
        try {
            counter = Integer.parseInt(schedule.id.substring(schedule.id.indexOf('-') + 1));
        } catch (RuntimeException ignored) {
            // foreign id shapes still work; the counter just skips them
        }
        AtomicInteger count = counters.computeIfAbsent(sessionId, ignored -> new AtomicInteger());
        count.accumulateAndGet(counter, (current, seen) -> Math.max(current, seen));
        arm(sessionId, schedule);
    }

    private void arm(String sessionId, Schedule schedule) {
        long now = System.currentTimeMillis();
        if (schedule.intervalSeconds > 0) {
            long intervalMs = schedule.intervalSeconds * 1000;
            if (schedule.dueAtMs < now) {
                // restart catch-up: the next unmissed occurrence
                long missed = (now - schedule.dueAtMs) / intervalMs + 1;
                schedule.dueAtMs += missed * intervalMs;
            }
        } else if (schedule.dueAtMs < now) {
            return; // stale one-shot: leave it in the log, do not fire
        }
        long delay = Math.max(0, schedule.dueAtMs - now);
        try {
            ScheduledFuture<?> future = timer.schedule(() -> fire(sessionId, schedule),
                    delay, TimeUnit.MILLISECONDS);
            armed.put(sessionId + "/" + schedule.id, future);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // timer already shut down (close()/rescan-after-close): the record
            // stays readable, it just cannot fire — same as after a crash
        }
    }

    private void fire(String sessionId, Schedule schedule) {
        if (schedule.cancelled) {
            return;
        }
        if (loop != null) {
            try {
                loop.followup(sessionId, schedule.prompt);
            } catch (RuntimeException e) {
                LOG.error("schedule: delivery of {} failed", schedule.id, e);
            }
        }
        if (schedule.intervalSeconds > 0) {
            schedule.dueAtMs = System.currentTimeMillis() + schedule.intervalSeconds * 1000;
            arm(sessionId, schedule);
        } else {
            // one-shot delivered: drop it from the active registry (the log
            // keeps the creation record)
            Map<String, Schedule> schedules = bySession.get(sessionId);
            if (schedules != null) {
                schedules.remove(schedule.id);
            }
        }
    }

    /** Creates a schedule; exactly one timing shape must be provided. */
    public Schedule create(String sessionId, String prompt, Long afterSeconds,
            Long atEpochMs, Long everySeconds) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("schedule: prompt must not be blank");
        }
        int shapes = (afterSeconds != null ? 1 : 0) + (atEpochMs != null ? 1 : 0)
                + (everySeconds != null ? 1 : 0);
        if (shapes != 1) {
            throw new IllegalArgumentException(
                    "schedule: pass exactly one of after_seconds, at, every_seconds");
        }
        long dueAtMs;
        long intervalSeconds = 0;
        if (afterSeconds != null) {
            if (afterSeconds < 1) {
                throw new IllegalArgumentException("schedule: after_seconds must be >= 1");
            }
            dueAtMs = System.currentTimeMillis() + afterSeconds * 1000;
        } else if (atEpochMs != null) {
            dueAtMs = atEpochMs;
        } else {
            if (everySeconds < MIN_REPEAT_SECONDS) {
                throw new IllegalArgumentException("schedule: every_seconds must be >= "
                        + MIN_REPEAT_SECONDS);
            }
            intervalSeconds = everySeconds;
            dueAtMs = System.currentTimeMillis() + everySeconds * 1000;
        }
        int next = counters.computeIfAbsent(sessionId, ignored -> new AtomicInteger()).incrementAndGet();
        Schedule schedule = new Schedule("sched-" + next, prompt, dueAtMs, intervalSeconds);
        sessions.append(sessionId, SessionEventType.SCHEDULE_SET, Map.of(
                SessionEvent.FIELD_SCHEDULE_ID, schedule.id,
                SessionEvent.FIELD_PROMPT, schedule.prompt,
                SessionEvent.FIELD_DUE_AT, schedule.dueAtMs,
                SessionEvent.FIELD_INTERVAL_SECONDS, schedule.intervalSeconds,
                SessionEvent.FIELD_CANCELLED, false));
        apply(sessionId, schedule, false);
        return schedule;
    }

    /** Marks the schedule cancelled (durable) and disarms its timer. */
    public boolean delete(String sessionId, String scheduleId) {
        Schedule schedule = get(sessionId, scheduleId);
        if (schedule == null || schedule.cancelled) {
            return false;
        }
        schedule.cancelled = true;
        cancelTimer(sessionId, scheduleId);
        sessions.append(sessionId, SessionEventType.SCHEDULE_SET, Map.of(
                SessionEvent.FIELD_SCHEDULE_ID, schedule.id,
                SessionEvent.FIELD_PROMPT, schedule.prompt,
                SessionEvent.FIELD_DUE_AT, schedule.dueAtMs,
                SessionEvent.FIELD_INTERVAL_SECONDS, schedule.intervalSeconds,
                SessionEvent.FIELD_CANCELLED, true));
        return true;
    }

    private void cancelTimer(String sessionId, String scheduleId) {
        ScheduledFuture<?> future = armed.remove(sessionId + "/" + scheduleId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /** Active (non-cancelled) schedules for the session, soonest first. */
    public List<Schedule> list(String sessionId) {
        Map<String, Schedule> schedules = bySession.get(sessionId);
        if (schedules == null) {
            return List.of();
        }
        List<Schedule> active = new ArrayList<>();
        for (Schedule schedule : schedules.values()) {
            if (!schedule.cancelled) {
                active.add(schedule);
            }
        }
        active.sort((a, b) -> Long.compare(a.dueAtMs, b.dueAtMs));
        return List.copyOf(active);
    }

    /** One schedule, or {@code null} when unknown. */
    public Schedule get(String sessionId, String scheduleId) {
        Map<String, Schedule> schedules = bySession.get(sessionId);
        return schedules == null ? null : schedules.get(scheduleId);
    }

    /** Stops the timer thread (plugin teardown / tests). */
    public void close() {
        timer.shutdownNow();
    }
}
