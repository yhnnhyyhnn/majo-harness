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
}
