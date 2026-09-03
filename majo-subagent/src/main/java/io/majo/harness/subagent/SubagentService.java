package io.majo.harness.subagent;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.majo.harness.agent.loop.AgentLoopService;
import io.majo.harness.session.SessionService;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The subagent service ({@code ctx.subagent}): delegation into a child agent.
 * {@link #delegate} opens a fresh child session and drives it through the same
 * {@code ctx.agentLoop} — a child agent is a new session with isolated history,
 * not a new process or a second loop. Nesting depth is guarded against
 * unbounded recursion (config {@code {maxDepth: <n>}}, default 3); exceeding
 * it fails loudly.
 *
 * <p>Delegations are synchronous today (single-threaded turns); per-agent
 * contexts for concurrent children arrive with the agent-context milestone.
 */
public final class SubagentService extends Service {

    public static final String NAME = "subagent";
    public static final int DEFAULT_MAX_DEPTH = 3;

    private final AgentLoopService loop;
    private final SessionService sessions;
    private final int maxDepth;
    private final AtomicInteger depth = new AtomicInteger();

    /** Bounded recent-delegation log surfaced to UI ({@code /api/subagents}). */
    private static final int MAX_RECENT = 25;
    private final ArrayDeque<Delegation> recent = new ArrayDeque<>();

    /** One delegation attempt as shown in the Subagents panel. */
    public record Delegation(String task, String status, String detail, long atMillis) {}

    /** A finished delegation: the child session (for transcripts/UI links) + text. */
    public record DelegationOutcome(String childSessionId, String answer) {}

    public SubagentService(Context ctx, Object config) {
        super(ctx, NAME);
        this.loop = require(ctx, AgentLoopService.NAME);
        this.sessions = require(ctx, SessionService.NAME);
        int max = DEFAULT_MAX_DEPTH;
        if (config instanceof java.util.Map<?, ?> map && map.get("maxDepth") instanceof Number number) {
            max = number.intValue();
        }
        if (max < 0) {
            throw new IllegalArgumentException("subagent: maxDepth must be >= 0, got " + max);
        }
        this.maxDepth = max;
    }

    private static <T> T require(Context ctx, String name) {
        T value = ctx.get(name);
        if (value == null) {
            throw new IllegalStateException(
                    "subagent: service \"" + name + "\" unavailable — declare it as an injection");
        }
        return value;
    }

    /**
     * Delegates {@code task} to a child session and returns its final text.
     * Recursive delegations nested deeper than {@code maxDepth} fail loudly.
     */
    public String delegate(String task) {
        return delegateWithChild(task).answer();
    }

    /** Like {@link #delegate}, also returning the child session id. */
    public DelegationOutcome delegateWithChild(String task) {
        int entered = depth.incrementAndGet();
        try {
            if (entered > maxDepth) {
                SubagentException blocked = new SubagentException("subagent: delegation depth " + entered
                        + " exceeds maxDepth " + maxDepth);
                record(new Delegation(task, "blocked", blocked.getMessage(), System.currentTimeMillis()));
                throw blocked;
            }
            String childSessionId = sessions.createSession();
            try {
                String answer = loop.runTurn(childSessionId, task);
                record(new Delegation(task, "done", preview(answer), System.currentTimeMillis()));
                return new DelegationOutcome(childSessionId, answer);
            } catch (RuntimeException failure) {
                record(new Delegation(task, "failed", String.valueOf(failure.getMessage()),
                        System.currentTimeMillis()));
                throw failure;
            }
        } finally {
            depth.decrementAndGet();
        }
    }

    /** Newest-first snapshot of recent delegations (never fails when empty). */
    public synchronized List<Delegation> recentRuns() {
        return List.copyOf(recent);
    }

    private synchronized void record(Delegation delegation) {
        recent.addFirst(delegation);
        while (recent.size() > MAX_RECENT) {
            recent.removeLast();
        }
    }

    private static String preview(String text) {
        if (text == null) {
            return null;
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }
}
