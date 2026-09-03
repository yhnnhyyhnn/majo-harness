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

    public SessionEvent {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    /** The text content of this event, or {@code null} when absent. */
    public String content() {
        Object value = fields.get(FIELD_CONTENT);
        return value == null ? null : String.valueOf(value);
    }
}
