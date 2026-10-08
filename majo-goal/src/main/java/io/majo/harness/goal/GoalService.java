package io.majo.harness.goal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.majo.harness.agent.loop.AgentLoopEvents;
import io.majo.harness.agent.loop.AgentLoopService;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The goal service ({@code ctx.goal}, dsh goal analog): one durable objective
 * per session, event-sourced as {@code GOAL_CHANGE} snapshots (or a clear
 * tombstone) with CAS revisions — every mutation carries
 * {@code (goalId, revision)} and a stale ref fails loudly. Phases:
 * {@code active → paused|blocked|complete}; {@code resume} re-arms from
 * paused/blocked; {@code clear} writes a tombstone and the id is never
 * reused.
 *
 * <p><b>Round driver</b> (dsh goal-round-driver, three load-bearing fences):
 * an armed active goal with round budget left renders its next round prompt
 * and queues it as a goal-produced followup. Fences: (1) the pending attempt
 * is re-validated at {@code agent/user-submit} — goal state moved, disarmed,
 * or a competing inbox entry means the round is rejected and nothing is
 * logged; (2) any non-goal inbox entry outranks the round (stand-down);
 * (3) the round budget blocks with {@code round-limit}. Activation is
 * process-local volatile state: a fresh runtime leaves the goal armed=false
 * (dsh restart parity) until a human resumes.
 *
 * <p>Config: {@code {blockedAfterConsecutiveRounds: 3}} — the hard floor of
 * admitted rounds before the model may self-report blocked (human requests
 * bypass it).
 */
public final class GoalService extends Service {

    public static final String NAME = "goal";
    public static final long DEFAULT_MAX_ROUNDS = 256;
    public static final int DEFAULT_BLOCKED_AFTER = 3;
    static final Logger LOG = LoggerFactory.getLogger(GoalService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Goal phases (dsh GoalPhase parity). */
    public enum Phase { ACTIVE, PAUSED, BLOCKED, COMPLETE }

    /** The durable goal snapshot. */
    public record Goal(String goalId, long revision, String objective, Phase phase,
            long maxRounds, String blockedCode, String blockedMessage,
            long createdAt, long updatedAt) {

        public Ref ref() {
            return new Ref(goalId, revision);
        }
    }

    /** The CAS reference every mutation carries. */
    public record Ref(String goalId, long revision) {}

    /** One queued round attempt (the driver's reservation). */
    private record Attempt(Ref goalRef, long round, String prompt) {}

    private final SessionService sessions;
    private final AgentLoopService loop;
    private final int blockedAfter;
    private final Map<String, Goal> current = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> seenIds = new ConcurrentHashMap<>();
    private final Map<String, Boolean> armed = new ConcurrentHashMap<>();
    private final Map<String, Attempt> pending = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Long> goalCounters = new ConcurrentHashMap<>();

    public GoalService(Context ctx, Object config, SessionService sessions, AgentLoopService loop) {
        super(ctx, NAME);
        this.sessions = sessions;
        this.loop = loop;
        this.blockedAfter = config instanceof Map<?, ?> map
                && map.get("blockedAfterConsecutiveRounds") instanceof Number number
                && number.intValue() > 0 ? number.intValue() : DEFAULT_BLOCKED_AFTER;
        rescan();
        // admission fence: validate the pending attempt before anything logs
        ctx.on(AgentLoopEvents.USER_SUBMIT, (thisArg, args) ->
                admitRound((String) args[0], (String) args[1]));
        // re-evaluate driving after every closed turn; a CANCELLED turn also
        // withdraws its queued round and disarms (dsh goal-round-driver
        // parity: a stale queued round must not be claimed ahead of a human
        // prompt, and the human regains control after an interruption)
        ctx.on(AgentLoopEvents.TURN_CLOSED, (thisArg, args) -> {
            String sessionId = (String) args[0];
            String reason = args.length > 1 && args[1] != null
                    ? String.valueOf(args[1]) : "completed";
            if ("aborted".equals(reason)) {
                if (loop != null) {
                    loop.withdrawQueuedGoalRounds(sessionId);
                }
                pending.remove(sessionId);
                armed.put(sessionId, false);
            }
            maybeDrive(sessionId);
            return null;
        });
    }

    /** Rebuilds goal state from every session's durable log (restart = disarmed). */
    final void rescan() {
        current.clear();
        seenIds.clear();
        armed.clear();
        pending.clear();
        for (String sessionId : sessions.sessionIds()) {
            for (SessionEvent event : sessions.events(sessionId)) {
                if (event.type() != SessionEventType.GOAL_CHANGE) {
                    continue;
                }
                apply(sessionId, event.fields());
            }
            // a fresh runtime never resumes armed (dsh restart parity)
            armed.put(sessionId, false);
        }
    }

    private void apply(String sessionId, Map<String, Object> fields) {
        String operation = String.valueOf(fields.get(SessionEvent.FIELD_OPERATION));
        if ("clear".equals(operation)) {
            String clearedId = String.valueOf(fields.get(SessionEvent.FIELD_GOAL_ID));
            current.remove(sessionId);
            seenIds.computeIfAbsent(sessionId, ignored -> new HashSet<>()).add(clearedId);
            armed.put(sessionId, false);
            return;
        }
        Goal goal = fromFields(fields);
        long expected = goal.revision() - 1;
        Goal existing = current.get(sessionId);
        long actual = existing == null ? 0 : existing.revision();
        if (goal.revision() != 1 && expected != actual) {
            throw new GoalException("goal: corrupt log on session \"" + sessionId
                    + "\": revision " + goal.revision() + " follows " + actual);
        }
        current.put(sessionId, goal);
        seenIds.computeIfAbsent(sessionId, ignored -> new HashSet<>()).add(goal.goalId());
        if ("create".equals(operation)) {
            armed.put(sessionId, true);
            goalCounters.merge(sessionId, 1L, Long::sum);
        }
    }    // ----- reads -----

    /** The current goal, or {@code null} when none is live. */
    public Goal get(String sessionId) {
        return current.get(sessionId);
    }

    /** Admitted goal rounds of the current goal (derived from goal-produced user messages). */
    public long roundsStarted(String sessionId) {
        Goal goal = current.get(sessionId);
        if (goal == null) {
            return 0;
        }
        long rounds = 0;
        for (SessionEvent event : sessions.events(sessionId)) {
            if (event.type() == SessionEventType.USER_MESSAGE
                    && "goal".equals(event.fields().get(SessionEvent.FIELD_PRODUCER))
                    && goal.goalId().equals(event.fields().get(SessionEvent.FIELD_GOAL_ID))) {
                rounds++;
            }
        }
        return rounds;
    }

    /**
     * Whether the open turn (everything after the last {@code TURN_START})
     * contains a real user message — the direct-human authority
     * (dsh requireDirectHuman parity).
     */
    public boolean hasDirectHumanInput(String sessionId) {
        return openTurnHas(sessionId, null, null);
    }

    /**
     * Whether the open turn contains a goal-produced message for this exact
     * goal — the round authority for autonomous complete/blocked.
     */
    public boolean hasGoalRoundInput(String sessionId, String goalId) {
        return openTurnHas(sessionId, "goal", goalId);
    }

    private boolean openTurnHas(String sessionId, String producer, String goalId) {
        List<SessionEvent> events = sessions.events(sessionId);
        long openFrom = 0;
        for (SessionEvent event : events) {
            if (event.type() == SessionEventType.TURN_START) {
                openFrom = event.seq();
            }
        }
        for (SessionEvent event : events) {
            if (event.seq() <= openFrom || event.type() != SessionEventType.USER_MESSAGE) {
                continue;
            }
            Object eventProducer = event.fields().get(SessionEvent.FIELD_PRODUCER);
            if (producer == null) {
                if (eventProducer == null || "user".equals(eventProducer)) {
                    return true;
                }
            } else if (producer.equals(eventProducer)
                    && goalId != null
                    && goalId.equals(String.valueOf(
                            event.fields().get(SessionEvent.FIELD_GOAL_ID)))) {
                return true;
            }
        }
        return false;
    }

    /** The configured blocked floor (interpolated into the system section). */
    public int blockedAfter() {
        return blockedAfter;
    }

    // ----- mutations (CAS + transition validation + durable append) -----

    /** Creates the session goal (only when none is live); arms the driver. */
    public Goal create(String sessionId, String objective, Long maxRounds) {
        Goal goal;
        synchronized (lock(sessionId)) {
            Goal existing = current.get(sessionId);
            if (existing != null && existing.phase() != Phase.COMPLETE) {
                throw new GoalException("goal: session \"" + sessionId
                        + "\" already has a live goal " + existing.ref()
                        + " — edit or clear it first");
            }
            long max = maxRounds == null ? DEFAULT_MAX_ROUNDS : maxRounds;
            if (max < 1) {
                throw new GoalException("goal: maxRounds must be >= 1");
            }
            long next = goalCounters.getOrDefault(sessionId, 0L) + 1;
            goalCounters.put(sessionId, next);
            long now = System.currentTimeMillis();
            goal = new Goal("goal-" + next, 1, objective, Phase.ACTIVE, max,
                    null, null, now, now);
            append(sessionId, "create", goal, true);
            current.put(sessionId, goal);
            armed.put(sessionId, true);
        }
        // armed at create: drive immediately (an idle-created goal has no
        // turn-close event to lean on)
        maybeDrive(sessionId);
        return goal;
    }

    /** Edits objective/maxRounds; the phase is immutable. */
    public Goal edit(String sessionId, Ref ref, String objective, Long maxRounds) {
        return mutate(sessionId, ref, "edit", goal -> {
            if (objective == null || objective.isBlank()) {
                throw new GoalException("goal: objective must not be blank");
            }
            long max = maxRounds == null ? goal.maxRounds() : maxRounds;
            if (max < 1) {
                throw new GoalException("goal: maxRounds must be >= 1");
            }
            return new Goal(goal.goalId(), goal.revision() + 1, objective, goal.phase(),
                    max, goal.blockedCode(), goal.blockedMessage(),
                    goal.createdAt(), System.currentTimeMillis());
        });
    }

    /** Pauses the goal; a host pause additionally aborts the running turn. */
    public Goal pause(String sessionId, Ref ref, boolean byHost) {
        Goal paused = mutate(sessionId, ref, "pause", goal -> {
            if (goal.phase() != Phase.ACTIVE) {
                throw new GoalException("goal: only an active goal can pause (phase "
                        + goal.phase() + ")");
            }
            return withPhase(goal, Phase.PAUSED, goal.revision() + 1);
        });
        if (byHost && loop != null) {
            loop.abort(sessionId);
        }
        return paused;
    }

    /** Resumes a paused/blocked goal (re-arms); the round budget still governs. */
    public Goal resume(String sessionId, Ref ref) {
        return mutate(sessionId, ref, "resume", goal -> {
            if (goal.phase() != Phase.PAUSED && goal.phase() != Phase.BLOCKED
                    && goal.phase() != Phase.ACTIVE) {
                throw new GoalException("goal: cannot resume a " + goal.phase() + " goal");
            }
            if (goal.phase() == Phase.ACTIVE && armed.getOrDefault(sessionId, false)) {
                throw new GoalException("goal: already active and armed");
            }
            if (roundsStarted(sessionId) >= goal.maxRounds()) {
                throw new GoalException("goal: round budget exhausted ("
                        + goal.maxRounds() + " rounds) — clear and create a new goal");
            }
            return withPhase(goal, Phase.ACTIVE, goal.revision() + 1);
        });
    }

    /** Marks the goal complete (terminal). */
    public Goal complete(String sessionId, Ref ref) {
        return mutate(sessionId, ref, "complete", goal -> {
            if (goal.phase() == Phase.COMPLETE) {
                throw new GoalException("goal: already complete");
            }
            return withPhase(goal, Phase.COMPLETE, goal.revision() + 1);
        });
    }

    /** Marks the goal blocked (active only) with a {code, message} reason. */
    public Goal block(String sessionId, Ref ref, String code, String message) {
        return mutate(sessionId, ref, "block", goal -> {
            if (goal.phase() != Phase.ACTIVE) {
                throw new GoalException("goal: only an active goal can block (phase "
                        + goal.phase() + ")");
            }
            if (code == null || code.isBlank() || message == null || message.isBlank()) {
                throw new GoalException("goal: blocking requires a code and a message");
            }
            return new Goal(goal.goalId(), goal.revision() + 1, goal.objective(),
                    Phase.BLOCKED, goal.maxRounds(), code, message,
                    goal.createdAt(), System.currentTimeMillis());
        });
    }

    /** Clears the goal (tombstone); the id is never reused. */
    public void clear(String sessionId) {
        synchronized (lock(sessionId)) {
            Goal goal = current.get(sessionId);
            if (goal == null) {
                return;
            }
            current.remove(sessionId);
            seenIds.computeIfAbsent(sessionId, ignored -> new HashSet<>()).add(goal.goalId());
            armed.put(sessionId, false);
            Map<String, Object> fields = new HashMap<>();
            fields.put(SessionEvent.FIELD_OPERATION, "clear");
            fields.put(SessionEvent.FIELD_GOAL_ID, goal.goalId());
            fields.put(SessionEvent.FIELD_GOAL_REVISION, goal.revision() + 1);
            sessions.append(sessionId, SessionEventType.GOAL_CHANGE, fields);
        }
    }

    // ----- round driver -----

    /** Offers the next goal round when armed, active, budgeted, and unopposed. */
    public void maybeDrive(String sessionId) {
        Goal goal = current.get(sessionId);
        if (goal == null || goal.phase() != Phase.ACTIVE
                || !armed.getOrDefault(sessionId, false)
                || pending.containsKey(sessionId)) {
            return;
        }
        long rounds = roundsStarted(sessionId);
        if (rounds >= goal.maxRounds()) {
            try {
                block(sessionId, goal.ref(), "round-limit",
                        "Goal reached its configured limit of " + goal.maxRounds() + " rounds.");
                armed.put(sessionId, false);
            } catch (RuntimeException e) {
                LOG.warn("goal: round-limit block failed on session \"{}\"", sessionId, e);
            }
            return;
        }
        if (hasCompetingWork(sessionId)) {
            // a human/job/schedule entry is queued: the goal stands down until
            // the session is idle again (re-evaluated on the next turn close)
            return;
        }
        long round = rounds + 1;
        String prompt = renderRoundPrompt(goal, round);
        pending.put(sessionId, new Attempt(goal.ref(), round, prompt));
        if (loop != null) {
            loop.followup(sessionId, prompt, Map.of(
                    SessionEvent.FIELD_PRODUCER, "goal",
                    SessionEvent.FIELD_GOAL_ID, goal.goalId(),
                    SessionEvent.FIELD_GOAL_REVISION, goal.revision(),
                    SessionEvent.FIELD_ROUND, round));
        }
        // with no loop (raw domain usage) the attempt stays pending: a manual
        // harness submits the round itself through the same admission fence
    }

    /**
     * The admission fence: when the submitted text is the pending attempt's
     * prompt, re-validate the whole goal state — mismatch rejects the turn
     * before anything is logged (the round number is not consumed). Always
     * returns the text on pass-through/admission (a null waterfall return
     * would break the pipeline).
     */
    private String admitRound(String sessionId, String text) {
        Attempt attempt = pending.get(sessionId);
        if (attempt == null || !attempt.prompt().equals(text)) {
            return text; // not ours; pass through
        }
        Goal goal = current.get(sessionId);
        boolean valid = goal != null
                && goal.goalId().equals(attempt.goalRef().goalId())
                && goal.revision() == attempt.goalRef().revision()
                && goal.phase() == Phase.ACTIVE
                && armed.getOrDefault(sessionId, false)
                && roundsStarted(sessionId) == attempt.round() - 1;
        // competing-at-admission fence (dsh parity): any other queued turn
        // work outranks the round even when the goal state is still valid —
        // the human prompt queued behind it must not wait a full goal round
        if (valid && loop != null && loop.hasCompetingTurnWork(sessionId)) {
            pending.remove(sessionId);
            throw new GoalException("goal: round stands down — other turn work is "
                    + "queued; the round will be re-offered once the session is idle");
        }
        pending.remove(sessionId);
        if (!valid) {
            throw new GoalException("goal: round reservation is stale — the goal state "
                    + "changed before admission; the round was not consumed [goal="
                    + (goal == null ? "none" : goal.goalId() + "/" + goal.revision() + "/"
                            + goal.phase() + " armed=" + armed.getOrDefault(sessionId, false))
                    + " attempt=" + attempt.goalRef() + "/" + attempt.round()
                    + " roundsStarted=" + roundsStarted(sessionId) + "]");
        }
        return text; // admitted: proceed unchanged
    }

    /** The exact round prompt a goal-produced user message must carry. */
    public static String renderRoundPrompt(Goal goal, long round) {
        String objective;
        try {
            objective = MAPPER.writeValueAsString(goal.objective());
        } catch (Exception e) {
            objective = "\"" + goal.objective() + "\"";
        }
        return "<goal_round>\n"
                + "Objective: " + objective + "\n"
                + "Round: " + round + "/" + goal.maxRounds() + "\n\n"
                + "Continue working toward the objective autonomously. When it is fully "
                + "achieved, call update_goal with action=complete. If the same blocking "
                + "condition has persisted for at least the configured number of consecutive "
                + "rounds, call update_goal with action=blocked and a code+message.";
    }

    /** Drops a stale pending attempt (turn aborted/failed before admission). */
    public void dropPendingAttempt(String sessionId) {
        pending.remove(sessionId);
    }

    // ----- plumbing -----

    private Goal mutate(String sessionId, Ref ref, String operation,
            java.util.function.Function<Goal, Goal> change) {
        Goal updated;
        synchronized (lock(sessionId)) {
            Goal goal = current.get(sessionId);
            if (goal == null) {
                throw new GoalException("goal: no live goal on session \"" + sessionId + "\"");
            }
            if (!goal.goalId().equals(ref.goalId()) || goal.revision() != ref.revision()) {
                throw new GoalException("goal: stale revision " + ref + " (current "
                        + goal.ref() + ")");
            }
            updated = change.apply(goal);
            if (updated.phase() == Phase.PAUSED || updated.phase() == Phase.COMPLETE
                    || updated.phase() == Phase.BLOCKED) {
                armed.put(sessionId, false);
            } else if ("resume".equals(operation)) {
                armed.put(sessionId, true);
            }
            append(sessionId, operation, updated, false);
            current.put(sessionId, updated);
        }
        if ("resume".equals(operation)) {
            // armed at resume: drive immediately (same idle-creation gap)
            maybeDrive(sessionId);
        }
        return updated;
    }

    private void append(String sessionId, String operation, Goal goal, boolean isNew) {
        Map<String, Object> fields = new HashMap<>();
        fields.put(SessionEvent.FIELD_OPERATION, operation);
        fields.put(SessionEvent.FIELD_GOAL_ID, goal.goalId());
        fields.put(SessionEvent.FIELD_GOAL_REVISION, goal.revision());
        fields.put(SessionEvent.FIELD_OBJECTIVE, goal.objective());
        fields.put(SessionEvent.FIELD_PHASE, goal.phase().name().toLowerCase());
        fields.put(SessionEvent.FIELD_MAX_ROUNDS, goal.maxRounds());
        if (goal.blockedCode() != null) {
            fields.put(SessionEvent.FIELD_BLOCKED_CODE, goal.blockedCode());
            fields.put(SessionEvent.FIELD_BLOCKED_MESSAGE, goal.blockedMessage());
        }
        fields.put(SessionEvent.FIELD_CREATED_AT, goal.createdAt());
        fields.put(SessionEvent.FIELD_UPDATED_AT, goal.updatedAt());
        sessions.append(sessionId, SessionEventType.GOAL_CHANGE, fields);
        if (isNew) {
            seenIds.computeIfAbsent(sessionId, ignored -> new HashSet<>()).add(goal.goalId());
        }
    }

    private void apply2(String sessionId, Map<String, Object> fields) {
        // placeholder removed
    }

    private static Goal fromFields(Map<String, Object> fields) {
        String phase = String.valueOf(fields.get(SessionEvent.FIELD_PHASE));
        return new Goal(
                String.valueOf(fields.get(SessionEvent.FIELD_GOAL_ID)),
                Long.parseLong(String.valueOf(fields.get(SessionEvent.FIELD_GOAL_REVISION))),
                String.valueOf(fields.get(SessionEvent.FIELD_OBJECTIVE)),
                Phase.valueOf(phase.toUpperCase()),
                Long.parseLong(String.valueOf(fields.get(SessionEvent.FIELD_MAX_ROUNDS))),
                textOrNull(fields, SessionEvent.FIELD_BLOCKED_CODE),
                textOrNull(fields, SessionEvent.FIELD_BLOCKED_MESSAGE),
                Long.parseLong(String.valueOf(fields.get(SessionEvent.FIELD_CREATED_AT))),
                Long.parseLong(String.valueOf(fields.get(SessionEvent.FIELD_UPDATED_AT))));
    }

    private static String textOrNull(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Goal withPhase(Goal goal, Phase phase, long revision) {
        return new Goal(goal.goalId(), revision, goal.objective(), phase, goal.maxRounds(),
                phase == Phase.BLOCKED ? goal.blockedCode() : null,
                phase == Phase.BLOCKED ? goal.blockedMessage() : null,
                goal.createdAt(), System.currentTimeMillis());
    }

    /** Any queued turn entry (non-goal work) outranks the round. */
    private boolean hasCompetingWork(String sessionId) {
        if (loop == null) {
            return false;
        }
        return loop.queuedCount(sessionId) > 0;
    }

    private Object lock(String sessionId) {
        return locks.computeIfAbsent(sessionId, ignored -> new Object());
    }
}
