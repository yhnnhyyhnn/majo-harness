package io.majo.harness.agent.loop;

/**
 * Events emitted by the agent loop around each step. Listeners participate
 * through the loop's context, keeping policy modules (compaction, meters)
 * decoupled from the driver.
 */
public final class AgentLoopEvents {

    private AgentLoopEvents() {}

    /**
     * Waterfall before every model request with {@code (sessionId,
     * List<ChatMessage> derivedHistory)} — the derived history WITHOUT the
     * system prompt (the loop re-adds it after the waterfall). The default
     * returns the history unchanged. A listener may persist context
     * adjustments first (dsh compaction) and return a rewritten history;
     * because the adjustment is durable before the request, "model-visible
     * means logged" holds.
     */
    public static final String BEFORE_REQUEST = "agent/before-request";

    /**
     * Waterfall at user-turn open with {@code (sessionId, String userText)},
     * fired inside the turn hold but BEFORE the message becomes durable —
     * listeners may return a {@code String} replacement (the loop logs the
     * returned text verbatim, preserving "model-visible means logged") or
     * throw to reject the submission outright (the turn fails loudly, nothing
     * was logged). The default returns the text unchanged.
     */
    public static final String USER_SUBMIT = "agent/user-submit";

    /**
     * Plain event after a turn closes durably with {@code (sessionId)} —
     * including aborted turns, excluding failed ones (they stay open). The
     * hooks Stop bridge hangs here.
     */
    public static final String TURN_CLOSED = "agent/turn-closed";
}
