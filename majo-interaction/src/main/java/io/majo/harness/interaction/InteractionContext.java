package io.majo.harness.interaction;

import java.util.List;
import java.util.function.Supplier;

/**
 * Thread-local context for the tool/interaction seam: which agent scope a
 * tool call belongs to (root turns have no label), whether approvals should be
 * granted automatically, and an optional allowed-tool whitelist for the scope.
 * Parallel subagent scopes each run on their own virtual thread, so the
 * thread-local is safe and torn down by {@link #run}.
 */
public final class InteractionContext {

    private static final ThreadLocal<String> AGENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> AUTO_APPROVE = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> ALLOWED_TOOLS = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION = new ThreadLocal<>();
    private static final ThreadLocal<Integer> DELEGATION_DEPTH = new ThreadLocal<>();

    private InteractionContext() {}

    /** The current agent label, or {@code null} for root turns. */
    public static String agent() {
        return AGENT.get();
    }

    /** Whether the current scope auto-approves gated tools. */
    public static boolean autoApprove() {
        return Boolean.TRUE.equals(AUTO_APPROVE.get());
    }

    /** The current scope's allowed-tool whitelist, or {@code null} (inherit all). */
    public static List<String> allowedTools() {
        return ALLOWED_TOOLS.get();
    }

    /**
     * The session the running turn appends to, or {@code null} outside a turn
     * (headless paths). The approval gate uses it to persist the durable
     * ask/decision audit pair into that session's log.
     */
    public static String sessionId() {
        return SESSION.get();
    }

    /**
     * How many delegation hops the current scope sits below the root (0 =
     * root turn; dsh delegationDepth parity). Reminder tools refuse depth
     * &gt; 0 — a delegated child must not schedule work into a session the
     * subagent routing owns.
     */
    public static int delegationDepth() {
        Integer depth = DELEGATION_DEPTH.get();
        return depth == null ? 0 : depth;
    }

    /**
     * Runs {@code body} with an explicit binding snapshot, restoring all five
     * values on exit. The agent loop's parallel-delegate path uses it to carry
     * the turn's bindings onto pool threads (a bare ThreadLocal does not
     * survive the thread hop).
     */
    public static <T> T runBound(String agent, boolean autoApprove, List<String> allowedTools,
            String sessionId, int delegationDepth, Supplier<T> body) {
        String previousAgent = AGENT.get();
        Boolean previousAuto = AUTO_APPROVE.get();
        List<String> previousAllowed = ALLOWED_TOOLS.get();
        String previousSession = SESSION.get();
        Integer previousDepth = DELEGATION_DEPTH.get();
        AGENT.set(agent);
        if (autoApprove) {
            AUTO_APPROVE.set(true);
        }
        if (allowedTools != null) {
            ALLOWED_TOOLS.set(List.copyOf(allowedTools));
        }
        SESSION.set(sessionId);
        DELEGATION_DEPTH.set(delegationDepth);
        try {
            return body.get();
        } finally {
            restore(AGENT, previousAgent);
            restore(AUTO_APPROVE, previousAuto);
            restore(ALLOWED_TOOLS, previousAllowed);
            restore(SESSION, previousSession);
            restore(DELEGATION_DEPTH, previousDepth);
        }
    }

    private static <T> void restore(ThreadLocal<T> local, T previous) {
        if (previous == null) {
            local.remove();
        } else {
            local.set(previous);
        }
    }

    /** Runs {@code body} bound to a session; always restores state. */
    public static <T> T runSession(String sessionId, Supplier<T> body) {
        String previous = SESSION.get();
        SESSION.set(sessionId);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                SESSION.remove();
            } else {
                SESSION.set(previous);
            }
        }
    }

    /** Runs {@code body} inside an agent scope; always restores state. */
    public static <T> T run(String agent, boolean autoApprove, Supplier<T> body) {
        return run(agent, autoApprove, null, body);
    }

    /** Runs {@code body} inside an agent scope with an allowed-tool whitelist. */
    public static <T> T run(String agent, boolean autoApprove, List<String> allowedTools,
            Supplier<T> body) {
        String previousAgent = AGENT.get();
        Boolean previousAuto = AUTO_APPROVE.get();
        List<String> previousAllowed = ALLOWED_TOOLS.get();
        AGENT.set(agent);
        if (autoApprove) {
            AUTO_APPROVE.set(true);
        }
        if (allowedTools != null) {
            ALLOWED_TOOLS.set(List.copyOf(allowedTools));
        }
        Integer previousDepth = DELEGATION_DEPTH.get();
        DELEGATION_DEPTH.set((previousDepth == null ? 0 : previousDepth) + 1);
        try {
            return body.get();
        } finally {
            // restore, not remove: delegations nest on the same thread, and the
            // enclosing scope's bindings must survive the child's teardown
            if (previousAgent == null) {
                AGENT.remove();
            } else {
                AGENT.set(previousAgent);
            }
            if (previousAuto == null) {
                AUTO_APPROVE.remove();
            } else {
                AUTO_APPROVE.set(previousAuto);
            }
            if (previousAllowed == null) {
                ALLOWED_TOOLS.remove();
            } else {
                ALLOWED_TOOLS.set(previousAllowed);
            }
            if (previousDepth == null) {
                DELEGATION_DEPTH.remove();
            } else {
                DELEGATION_DEPTH.set(previousDepth);
            }
        }
    }
}
