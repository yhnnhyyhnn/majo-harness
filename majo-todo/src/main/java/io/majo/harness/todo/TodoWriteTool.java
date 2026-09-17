package io.majo.harness.todo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code todo_write} (dsh todo): the model maintains its task list by
 * replacing the whole list each call — no partial mutations, so the durable
 * log and the projection always agree. Runs inside a turn: the session comes
 * from {@link InteractionContext#sessionId()}.
 */
public final class TodoWriteTool implements Tool {

    public static final String NAME = "todo_write";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> STATUSES = Set.of("pending", "in_progress", "completed");
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Replace the session task list with this exact list. Use one call with the full "
                    + "list every time it changes: mark an item in_progress before starting it and "
                    + "completed immediately after finishing it.",
            schema());

    private final SessionService sessions;

    public TodoWriteTool(SessionService sessions) {
        this.sessions = sessions;
    }

    private static JsonNode schema() {
        ObjectNode content = MAPPER.createObjectNode();
        content.put("type", "string");
        ObjectNode status = MAPPER.createObjectNode();
        status.put("type", "string");
        status.putArray("enum").add("pending").add("in_progress").add("completed");
        ObjectNode entryProperties = MAPPER.createObjectNode();
        entryProperties.set("content", content);
        entryProperties.set("status", status);
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("type", "object");
        entry.set("properties", entryProperties);
        ObjectNode todoSchema = MAPPER.createObjectNode();
        todoSchema.put("type", "array");
        todoSchema.set("items", entry);
        todoSchema.put("description", "The complete list in execution order.");
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("todos", todoSchema);
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("todos");
        return schema;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String sessionId = InteractionContext.sessionId();
        if (sessionId == null) {
            return ToolResult.error("todo_write runs inside a turn; no session is bound");
        }
        final List<Map<String, Object>> items;
        try {
            JsonNode root = MAPPER.readTree(call.arguments() == null ? "{}" : call.arguments());
            JsonNode todos = root.path("todos");
            if (!todos.isArray() || todos.isEmpty()) {
                return ToolResult.error("todos must be a non-empty array");
            }
            items = new ArrayList<>();
            for (JsonNode item : todos) {
                String content = item.path("content").asText("");
                String status = item.path("status").asText("pending");
                if (content.isBlank()) {
                    return ToolResult.error("todo content must not be blank");
                }
                if (!STATUSES.contains(status)) {
                    return ToolResult.error("unknown todo status \"" + status
                            + "\"; use pending, in_progress, or completed");
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put(SessionEvent.FIELD_CONTENT, content);
                entry.put(SessionEvent.FIELD_STATUS, status);
                items.add(entry);
            }
        } catch (Exception e) {
            return ToolResult.error("todo_write cannot parse arguments: " + e.getMessage());
        }
        sessions.append(sessionId, SessionEventType.TODO_SET,
                Map.of(SessionEvent.FIELD_ITEMS, items));
        long open = items.stream().filter(item -> !"completed".equals(item.get(
                SessionEvent.FIELD_STATUS))).count();
        return ToolResult.ok("recorded " + items.size() + " todo"
                + (items.size() == 1 ? "" : "s") + " (" + open + " open)", Map.of("count", items.size()));
    }
}
