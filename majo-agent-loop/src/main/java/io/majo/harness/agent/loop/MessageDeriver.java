package io.majo.harness.agent.loop;

import io.majo.harness.llm.ChatMessage;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.tools.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Projects model history from the session log: the model-visible input of a
 * request is derived exclusively from durable {@link SessionEvent events}, so
 * any request can be reconstructed from the log (model-visible implies
 * logged). Turn markers are skipped; assistant rounds keep their text and tool
 * calls; tool results line up by {@code toolCallId}.
 */
public final class MessageDeriver {

    private MessageDeriver() {}

    /** Derives the model history for {@code events} in log order. */
    public static List<ChatMessage> derive(List<SessionEvent> events) {
        List<ChatMessage> messages = new ArrayList<>();
        for (SessionEvent event : events) {
            switch (event.type()) {
                case TURN_START, TURN_END, REQUEST_HEADER, APPROVAL_REQUESTED, APPROVAL_DECIDED,
                        TODO_SET, PLAN_SET, SCHEDULE_SET,
                        WORKFLOW_START, WORKFLOW_STEP, WORKFLOW_END -> {
                    // turn boundaries, request headers, the approval audit
                    // pair, todo/plan/schedule bookkeeping, and workflow
                    // progress are not model messages (tool results and
                    // spliced notes carry the model text; schedules deliver
                    // their prompt as a turn)
                }
                case CONTEXT_COMPACTION -> {
                    // dsh compaction: everything logged before this event is
                    // replaced by its summary — the summary itself is durable
                    // model-visible content, so the invariant still holds
                    messages.clear();
                    if (event.content() != null && !event.content().isBlank()) {
                        messages.add(ChatMessage.user("[conversation summary] " + event.content()));
                    }
                }
                case USER_MESSAGE, CONTEXT_NOTE -> messages.add(ChatMessage.user(event.content()));
                case ASSISTANT_MESSAGE -> messages.add(ChatMessage.assistant(
                        event.content(), toToolCalls(event.fields())));
                case TOOL_RESULT -> messages.add(ChatMessage.toolResult(
                        stringField(event.fields(), SessionEvent.FIELD_TOOL_CALL_ID),
                        event.content()));
            }
        }
        return List.copyOf(messages);
    }

    @SuppressWarnings("unchecked")
    private static List<ToolCall> toToolCalls(Map<String, Object> fields) {
        Object raw = fields.get(SessionEvent.FIELD_TOOL_CALLS);
        if (!(raw instanceof List<?> calls) || calls.isEmpty()) {
            return List.of();
        }
        List<ToolCall> result = new ArrayList<>();
        for (Object item : calls) {
            if (item instanceof Map<?, ?> call) {
                result.add(new ToolCall(
                        stringField(call, SessionEvent.FIELD_TOOL_CALL_ID),
                        stringField(call, SessionEvent.FIELD_TOOL_NAME),
                        stringField(call, SessionEvent.FIELD_ARGUMENTS)));
            }
        }
        return List.copyOf(result);
    }

    private static String stringField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
