package io.majo.harness.session;

/**
 * Kinds of durable session-log events.
 *
 * <p>These mirror the durable dsh turn/step events: a turn opens with
 * {@link #TURN_START}, records every model-visible input and output in order
 * (user text, one event per assistant round including its tool calls, one
 * event per tool result), and closes with {@link #TURN_END}.
 */
public enum SessionEventType {
    /** A turn opened (one request series from the user). */
    TURN_START,
    /** A user message entered the session. */
    USER_MESSAGE,
    /** One assistant round: text content and/or the tool calls the model asked for. */
    ASSISTANT_MESSAGE,
    /** One tool execution result, referencing its assistant tool call. */
    TOOL_RESULT,
    /** The turn closed: nothing further is owed. */
    TURN_END,
    /** One model request as composed: model, system prompt, offered tool names. */
    REQUEST_HEADER
}
