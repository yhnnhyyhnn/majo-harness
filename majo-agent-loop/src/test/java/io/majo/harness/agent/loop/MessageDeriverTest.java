package io.majo.harness.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import io.majo.harness.llm.ChatMessage;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageDeriverTest {

    private static SessionEvent event(long seq, SessionEventType type, Map<String, Object> fields) {
        return new SessionEvent(seq, type, seq, fields);
    }

    @Test
    void derivesModelHistoryFromLog() {
        List<SessionEvent> events = List.of(
                event(1, SessionEventType.TURN_START, Map.of()),
                event(2, SessionEventType.USER_MESSAGE, Map.of(SessionEvent.FIELD_CONTENT, "1+2")),
                event(3, SessionEventType.ASSISTANT_MESSAGE, Map.of(
                        SessionEvent.FIELD_TOOL_CALLS, List.of(Map.of(
                                SessionEvent.FIELD_TOOL_CALL_ID, "c1",
                                SessionEvent.FIELD_TOOL_NAME, "calc",
                                SessionEvent.FIELD_ARGUMENTS, "{\"expression\":\"1+2\"}")))),
                event(4, SessionEventType.TOOL_RESULT, Map.of(
                        SessionEvent.FIELD_TOOL_CALL_ID, "c1",
                        SessionEvent.FIELD_OK, true,
                        SessionEvent.FIELD_CONTENT, "3")),
                event(5, SessionEventType.ASSISTANT_MESSAGE,
                        Map.of(SessionEvent.FIELD_CONTENT, "calculated: 3")),
                event(6, SessionEventType.TURN_END, Map.of()));

        List<ChatMessage> messages = MessageDeriver.derive(events);

        assertThat(messages).hasSize(4);
        assertThat(messages).extracting(ChatMessage::role)
                .containsExactly(
                        io.majo.harness.llm.ChatRole.USER,
                        io.majo.harness.llm.ChatRole.ASSISTANT,
                        io.majo.harness.llm.ChatRole.TOOL,
                        io.majo.harness.llm.ChatRole.ASSISTANT);
        assertThat(messages.get(1).toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("c1");
            assertThat(call.name()).isEqualTo("calc");
            assertThat(call.arguments()).contains("1+2");
        });
        assertThat(messages.get(2).toolCallId()).isEqualTo("c1");
        assertThat(messages.get(2).content()).isEqualTo("3");
        assertThat(messages.get(3).content()).isEqualTo("calculated: 3");
    }

    @Test
    void requestHeadersAreNotModelMessages() {
        List<SessionEvent> events = List.of(
                event(1, SessionEventType.TURN_START, Map.of()),
                event(2, SessionEventType.REQUEST_HEADER, Map.of(
                        SessionEvent.FIELD_MODEL, "mock",
                        SessionEvent.FIELD_SYSTEM_PROMPT, "you are helpful",
                        SessionEvent.FIELD_TOOL_NAMES, List.of("calc"))),
                event(3, SessionEventType.USER_MESSAGE, Map.of(SessionEvent.FIELD_CONTENT, "hi")),
                event(4, SessionEventType.REQUEST_HEADER, Map.of(
                        SessionEvent.FIELD_MODEL, "mock",
                        SessionEvent.FIELD_SYSTEM_PROMPT, "you are helpful",
                        SessionEvent.FIELD_TOOL_NAMES, List.of("calc"))),
                event(5, SessionEventType.ASSISTANT_MESSAGE,
                        Map.of(SessionEvent.FIELD_CONTENT, "hello")));

        List<ChatMessage> messages = MessageDeriver.derive(events);
        assertThat(messages).extracting(ChatMessage::role)
                .containsExactly(
                        io.majo.harness.llm.ChatRole.USER,
                        io.majo.harness.llm.ChatRole.ASSISTANT);
    }
}
