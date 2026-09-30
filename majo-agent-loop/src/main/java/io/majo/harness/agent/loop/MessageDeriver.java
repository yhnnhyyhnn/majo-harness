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
        return fold(events, Long.MAX_VALUE);
    }

    /**
     * Derives only the messages contributed by events at or before
     * {@code upToSeq} — the compaction region (dsh region parity: the
     * summarizer sees exactly what the summary is allowed to cover).
     */
    public static List<ChatMessage> deriveRegion(List<SessionEvent> events, long upToSeq) {
        return fold(events, upToSeq);
    }

    private static List<ChatMessage> fold(List<SessionEvent> events, long seqCap) {
        List<ChatMessage> messages = new ArrayList<>();
        // parallel to messages: the seq of the event that contributed each one
        List<Long> seqs = new ArrayList<>();
        for (SessionEvent event : events) {
            if (event.seq() > seqCap) {
                continue; // outside the region cap
            }
            foldEvent(event, messages, seqs);
        }
        return List.copyOf(messages);
    }

    private static void foldEvent(SessionEvent event, List<ChatMessage> messages, List<Long> seqs) {
        switch (event.type()) {
                case TURN_START, TURN_END, REQUEST_HEADER, APPROVAL_REQUESTED, APPROVAL_DECIDED,
                        TODO_SET, PLAN_SET, SCHEDULE_SET,
                        WORKFLOW_START, WORKFLOW_STEP, WORKFLOW_END,
                        HOOK_INVOKED, HOOK_RESULT -> {
                    // turn boundaries, request headers, the approval audit
                    // pair, todo/plan/schedule bookkeeping, workflow
                    // progress, and the hook audit pair are not model
                    // messages (tool results and spliced notes carry the
                    // model text; schedules deliver their prompt as a turn;
                    // hook context reaches the model as CONTEXT_NOTE)
                }
                case CONTEXT_COMPACTION -> {
                    // dsh compaction with region selection: messages
                    // contributed at or before upToSeq are replaced by the
                    // summary; the priced tail (events after the cut, logged
                    // before this compaction event) survives verbatim. A
                    // missing/zero upToSeq keeps the legacy whole-history
                    // semantics. Filtering is IN PLACE — the caller owns the
                    // list references.
                    long upToSeq = upToSeq(event);
                    if (upToSeq <= 0) {
                        messages.clear();
                        seqs.clear();
                    } else {
                        for (int i = messages.size() - 1; i >= 0; i--) {
                            if (seqs.get(i) <= upToSeq) {
                                messages.remove(i);
                                seqs.remove(i);
                            }
                        }
                    }
                    if (event.content() != null && !event.content().isBlank()) {
                        messages.add(0, ChatMessage.user("[conversation summary] " + event.content()));
                        seqs.add(0, event.seq());
                    }
                }
                case USER_MESSAGE, CONTEXT_NOTE -> {
                    messages.add(ChatMessage.user(event.content()));
                    seqs.add(event.seq());
                }
                case ASSISTANT_MESSAGE -> {
                    messages.add(ChatMessage.assistant(
                            event.content(), toToolCalls(event.fields())));
                    seqs.add(event.seq());
                }
                case TOOL_RESULT -> {
                    messages.add(ChatMessage.toolResult(
                            stringField(event.fields(), SessionEvent.FIELD_TOOL_CALL_ID),
                            event.content()));
                    seqs.add(event.seq());
                }
            }
    }

    private static long upToSeq(SessionEvent event) {
        Object value = event.fields().get(SessionEvent.FIELD_UP_TO_SEQ);
        return value == null ? 0 : Long.parseLong(String.valueOf(value));
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
