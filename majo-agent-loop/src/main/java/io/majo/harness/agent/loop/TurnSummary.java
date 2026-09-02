package io.majo.harness.agent.loop;

import io.majo.harness.session.SessionProjection;
import io.majo.harness.session.TypedSessionEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code turnSummary} projection contributed by the agent loop: per-session
 * fold of the durable turn events into a typed state, readable by hosts (UI,
 * tooling) through {@code SessionProjections.require("turnSummary")} without
 * rescanning the log.
 */
public final class TurnSummary implements SessionProjection {

    public static final String KEY = "turnSummary";

    /** Immutable per-session summary (rebuilt on each relevant event). */
    public record Summary(boolean turnOpen, int turnCount, int assistantRounds, int toolCalls,
                          String lastUserText, String lastFinalText) {}

    private final Map<String, Summary> state = new ConcurrentHashMap<>();

    @Override
    public void onEvent(String sessionId, TypedSessionEvent event) {
        Summary current = state.getOrDefault(sessionId, new Summary(false, 0, 0, 0, null, null));
        Summary next = switch (event) {
            case TypedSessionEvent.TurnStart ignored -> new Summary(
                    true, current.turnCount(), current.assistantRounds(), current.toolCalls(),
                    current.lastUserText(), current.lastFinalText());
            case TypedSessionEvent.TurnEnd ignored -> new Summary(
                    false, current.turnCount() + 1, current.assistantRounds(), current.toolCalls(),
                    current.lastUserText(), current.lastFinalText());
            case TypedSessionEvent.UserMessage message -> new Summary(
                    current.turnOpen(), current.turnCount(), current.assistantRounds(), current.toolCalls(),
                    message.content(), current.lastFinalText());
            case TypedSessionEvent.RequestHeader ignored -> current;
            case TypedSessionEvent.AssistantMessage message -> new Summary(
                    current.turnOpen(), current.turnCount(), current.assistantRounds() + 1, current.toolCalls(),
                    current.lastUserText(),
                    message.toolCalls().isEmpty() ? message.content() : current.lastFinalText());
            case TypedSessionEvent.ToolResult ignored -> new Summary(
                    current.turnOpen(), current.turnCount(), current.assistantRounds(), current.toolCalls() + 1,
                    current.lastUserText(), current.lastFinalText());
        };
        state.put(sessionId, next);
    }

    /** The typed per-session state (defaults for unknown sessions). */
    public Summary summary(String sessionId) {
        return state.getOrDefault(sessionId, new Summary(false, 0, 0, 0, null, null));
    }
}
