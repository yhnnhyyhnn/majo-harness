package io.majo.harness.interaction;

import java.util.function.Supplier;

/**
 * Thread-local context for the tool/interaction seam: which agent scope a
 * tool call belongs to (root turns have no label) and whether approvals
 * should be granted automatically for that scope (per-agent policy).
 * Parallel subagent scopes each run on their own virtual thread, so the
 * thread-local is safe and torn down by {@link #run}.
 */
public final class InteractionContext {

    private static final ThreadLocal<String> AGENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> AUTO_APPROVE = new ThreadLocal<>();

    private InteractionContext() {}

    /** The current agent label, or {@code null} for root turns. */
    public static String agent() {
        return AGENT.get();
    }

    /** Whether the current scope auto-approves gated tools. */
    public static boolean autoApprove() {
        return Boolean.TRUE.equals(AUTO_APPROVE.get());
    }

    /** Runs {@code body} inside an agent scope; always restores state. */
    public static <T> T run(String agent, boolean autoApprove, Supplier<T> body) {
        AGENT.set(agent);
        if (autoApprove) {
            AUTO_APPROVE.set(true);
        }
        try {
            return body.get();
        } finally {
            AGENT.remove();
            AUTO_APPROVE.remove();
        }
    }
}
