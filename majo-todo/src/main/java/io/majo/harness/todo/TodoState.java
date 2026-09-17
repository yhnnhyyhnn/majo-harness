package io.majo.harness.todo;

import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionProjection;
import io.majo.harness.session.TypedSessionEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code todo} projection: folds {@code TODO_SET} events (dsh todo) into
 * the per-session todo list. Whole-replacement semantics — each event carries
 * the complete list, so the fold is just "take the latest".
 */
public final class TodoState implements SessionProjection {

    public static final String KEY = "todo";

    /** One todo entry (the projection's read model). */
    public record Item(String content, String status) {}

    private final Map<String, List<Item>> state = new ConcurrentHashMap<>();

    @Override
    public void onEvent(String sessionId, TypedSessionEvent event) {
        if (event instanceof TypedSessionEvent.TodoSet set) {
            List<Item> items = set.items().stream()
                    .map(entry -> new Item(entry.content(), entry.status()))
                    .toList();
            state.put(sessionId, items);
        }
    }

    /** The current todo list for the session (empty when none was written). */
    public List<Item> items(String sessionId) {
        List<Item> items = state.get(sessionId);
        return items == null ? List.of() : items;
    }

    /** Wire shape shared with {@code WebApiModels.TodoItem} consumers. */
    public static List<Map<String, Object>> toFields(List<Item> items) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Item item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(SessionEvent.FIELD_CONTENT, item.content() == null ? "" : item.content());
            entry.put(SessionEvent.FIELD_STATUS, item.status() == null ? "pending" : item.status());
            fields.add(entry);
        }
        return List.copyOf(fields);
    }
}
