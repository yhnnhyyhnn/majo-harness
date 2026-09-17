package io.majo.harness.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed view of a {@link SessionEvent}: each kind carries a closed payload
 * record instead of the open {@code fields} map, so projections and host
 * consumers switch on a sealed type rather than stringly-typed maps
 * (mirroring dsh's merge-extensible {@code SessionEventMap}).
 *
 * <p>Parsing is one-way today ({@link #of}); event *writing* keeps building
 * field maps through the {@link SessionEvent} constants. A malformed event
 * (wrong payload shape for its kind) fails loudly when parsed.
 */
public sealed interface TypedSessionEvent {

    /** The turn opened. */
    record TurnStart() implements TypedSessionEvent {}

    /** The turn closed. */
    record TurnEnd() implements TypedSessionEvent {}

    /** A user message. */
    record UserMessage(String content) implements TypedSessionEvent {}

    /**
     * One assistant round: optional text and the tool calls it requested, as
     * {@link ToolCallEntry the log's serialized form}.
     */
    record AssistantMessage(String content, List<ToolCallEntry> toolCalls) implements TypedSessionEvent {
        public AssistantMessage {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    /** One executed tool result. */
    record ToolResult(String toolCallId, String name, boolean ok, String content)
            implements TypedSessionEvent {}

    /** One model request as composed. */
    record RequestHeader(String model, String systemPrompt, List<String> toolNames)
            implements TypedSessionEvent {
        public RequestHeader {
            toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        }
    }

    /** Context spliced in without waking the loop (dsh "inject"). */
    record ContextNote(String content) implements TypedSessionEvent {}

    /** An approval ask (durable audit; the open turn wraps it). */
    record ApprovalRequested(String approvalId, String summary, String details, String agent)
            implements TypedSessionEvent {}

    /** An approval resolution: allow/deny and the deciding source (policy|handler). */
    record ApprovalDecided(String approvalId, String decision, String source)
            implements TypedSessionEvent {}

    /** The session todo list replaced wholesale. */
    record TodoSet(List<TodoEntry> items) implements TypedSessionEvent {
        public TodoSet {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** One todo entry as stored on a {@link TodoSet}. */
    record TodoEntry(String content, String status) {}

    /** The session plan state changed. */
    record PlanSet(boolean active, String plan) implements TypedSessionEvent {}

    /** A serialized assistant tool call (the log's wire form of a ToolCall). */
    record ToolCallEntry(String id, String name, String arguments) {}

    /** Parses a stored event into its typed form; malformed payloads fail loudly. */
    static TypedSessionEvent of(SessionEvent event) {
        Map<String, Object> fields = event.fields();
        return switch (event.type()) {
            case TURN_START -> new TurnStart();
            case TURN_END -> new TurnEnd();
            case USER_MESSAGE -> new UserMessage(text(fields, SessionEvent.FIELD_CONTENT));
            case ASSISTANT_MESSAGE -> new AssistantMessage(
                    text(fields, SessionEvent.FIELD_CONTENT), toolCalls(fields, event.seq()));
            case TOOL_RESULT -> new ToolResult(
                    text(fields, SessionEvent.FIELD_TOOL_CALL_ID),
                    text(fields, SessionEvent.FIELD_TOOL_NAME),
                    ok(fields),
                    text(fields, SessionEvent.FIELD_CONTENT));
            case REQUEST_HEADER -> new RequestHeader(
                    text(fields, SessionEvent.FIELD_MODEL),
                    text(fields, SessionEvent.FIELD_SYSTEM_PROMPT),
                    strings(fields, SessionEvent.FIELD_TOOL_NAMES));
            case CONTEXT_NOTE -> new ContextNote(text(fields, SessionEvent.FIELD_CONTENT));
            case APPROVAL_REQUESTED -> new ApprovalRequested(
                    text(fields, SessionEvent.FIELD_APPROVAL_ID),
                    text(fields, SessionEvent.FIELD_SUMMARY),
                    text(fields, SessionEvent.FIELD_DETAILS),
                    text(fields, SessionEvent.FIELD_AGENT));
            case APPROVAL_DECIDED -> new ApprovalDecided(
                    text(fields, SessionEvent.FIELD_APPROVAL_ID),
                    text(fields, SessionEvent.FIELD_DECISION),
                    text(fields, SessionEvent.FIELD_SOURCE));
            case TODO_SET -> new TodoSet(todoItems(fields, event.seq()));
            case PLAN_SET -> new PlanSet(
                    Boolean.parseBoolean(text(fields, SessionEvent.FIELD_ACTIVE)),
                    text(fields, SessionEvent.FIELD_PLAN));
        };
    }

    @SuppressWarnings("unchecked")
    private static List<ToolCallEntry> toolCalls(Map<String, Object> fields, long seq) {
        Object raw = fields.get(SessionEvent.FIELD_TOOL_CALLS);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> items)) {
            throw new IllegalArgumentException("session event " + seq + ": toolCalls must be a list");
        }
        List<ToolCallEntry> entries = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> call)) {
                throw new IllegalArgumentException("session event " + seq + ": tool call entry must be an object");
            }
            entries.add(new ToolCallEntry(
                    text(call, SessionEvent.FIELD_TOOL_CALL_ID),
                    text(call, SessionEvent.FIELD_TOOL_NAME),
                    text(call, SessionEvent.FIELD_ARGUMENTS)));
        }
        return List.copyOf(entries);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> fields, String key) {
        Object raw = fields.get(key);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> items)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            result.add(item == null ? null : String.valueOf(item));
        }
        return List.copyOf(result);
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<TodoEntry> todoItems(Map<String, Object> fields, long seq) {
        Object raw = fields.get(SessionEvent.FIELD_ITEMS);
        if (!(raw instanceof List<?> items)) {
            throw new IllegalArgumentException("session event " + seq + ": items must be a list");
        }
        List<TodoEntry> entries = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException("session event " + seq + ": todo entry must be an object");
            }
            entries.add(new TodoEntry(
                    text(entry, SessionEvent.FIELD_CONTENT),
                    text(entry, SessionEvent.FIELD_STATUS)));
        }
        return List.copyOf(entries);
    }

    private static boolean ok(Map<String, Object> fields) {
        Object value = fields.get(SessionEvent.FIELD_OK);
        return value instanceof Boolean b && b;
    }
}
