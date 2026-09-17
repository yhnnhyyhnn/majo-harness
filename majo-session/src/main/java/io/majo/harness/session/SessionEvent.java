package io.majo.harness.session;

import java.util.Map;

/**
 * One durable, append-only session event.
 *
 * <p>{@code fields} is a JSON-serializable payload map whose keys depend on
 * the event kind; the constants on this record name them. Keeping the payload
 * open keeps the log forwards-compatible; typed projections (dsh's
 * {@code SessionEventMap}) arrive with the typed-projection milestone.
 */
public record SessionEvent(long seq, SessionEventType type, long timestamp, Map<String, Object> fields) {

    public static final String FIELD_CONTENT = "content";
    /** Assistant tool calls: a list of {@code {id, name, arguments}} maps. */
    public static final String FIELD_TOOL_CALLS = "toolCalls";
    public static final String FIELD_TOOL_CALL_ID = "toolCallId";
    public static final String FIELD_TOOL_NAME = "name";
    /** JSON arguments string of a serialized tool call entry. */
    public static final String FIELD_ARGUMENTS = "arguments";
    /** Whether the tool execution succeeded. */
    public static final String FIELD_OK = "ok";
    /** Structured tool metadata (exit code, hits…) that is not model text. */
    public static final String FIELD_DATA = "data";
    /** The model id of a {@link SessionEventType#REQUEST_HEADER}. */
    public static final String FIELD_MODEL = "model";
    /** The system prompt of a {@link SessionEventType#REQUEST_HEADER}. */
    public static final String FIELD_SYSTEM_PROMPT = "systemPrompt";
    /** Tool names offered by a {@link SessionEventType#REQUEST_HEADER}. */
    public static final String FIELD_TOOL_NAMES = "toolNames";
    /** Correlation id of an {@link SessionEventType#APPROVAL_REQUESTED} / {@code APPROVAL_DECIDED} pair. */
    public static final String FIELD_APPROVAL_ID = "approvalId";
    /** Human-readable subject of an approval ask. */
    public static final String FIELD_SUMMARY = "summary";
    /** Context payload of an approval ask. */
    public static final String FIELD_DETAILS = "details";
    /** Originating agent label of an approval ask (root turns omit it). */
    public static final String FIELD_AGENT = "agent";
    /** Resolution of {@link SessionEventType#APPROVAL_DECIDED}: {@code allow} | {@code deny}. */
    public static final String FIELD_DECISION = "decision";
    /** Who decided: {@code policy} (session policy / auto-approve) | {@code handler}. */
    public static final String FIELD_SOURCE = "source";
    /** The todo entries of a {@link SessionEventType#TODO_SET}: a list of {@code {content, status}} maps. */
    public static final String FIELD_ITEMS = "items";
    /** Lifecycle status of one todo entry: {@code pending} | {@code in_progress} | {@code completed}. */
    public static final String FIELD_STATUS = "status";
    /** Active flag of a {@link SessionEventType#PLAN_SET}. */
    public static final String FIELD_ACTIVE = "active";
    /** Plan text of a {@link SessionEventType#PLAN_SET}. */
    public static final String FIELD_PLAN = "plan";
    /** Correlation id of a {@link SessionEventType#SCHEDULE_SET}. */
    public static final String FIELD_SCHEDULE_ID = "scheduleId";
    /** Delivered-as-turn prompt of a {@link SessionEventType#SCHEDULE_SET}. */
    public static final String FIELD_PROMPT = "prompt";
    /** Next due time (epoch millis) of a {@link SessionEventType#SCHEDULE_SET}. */
    public static final String FIELD_DUE_AT = "dueAt";
    /** Repeat interval seconds of a {@link SessionEventType#SCHEDULE_SET} ({@code 0} = one-shot). */
    public static final String FIELD_INTERVAL_SECONDS = "intervalSeconds";
    /** Deletion marker of a {@link SessionEventType#SCHEDULE_SET}. */
    public static final String FIELD_CANCELLED = "cancelled";

    public SessionEvent {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    /** The text content of this event, or {@code null} when absent. */
    public String content() {
        Object value = fields.get(FIELD_CONTENT);
        return value == null ? null : String.valueOf(value);
    }
}
