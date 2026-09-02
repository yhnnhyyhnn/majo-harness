package io.majo.harness.subagent;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.majo.harness.agent.loop.AgentLoopService;
import io.majo.harness.session.SessionService;
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
        int entered = depth.incrementAndGet();
        try {
            if (entered > maxDepth) {
                throw new SubagentException("subagent: delegation depth " + entered
                        + " exceeds maxDepth " + maxDepth);
            }
            String childSessionId = sessions.createSession();
            return loop.runTurn(childSessionId, task);
        } finally {
            depth.decrementAndGet();
        }
    }
}
