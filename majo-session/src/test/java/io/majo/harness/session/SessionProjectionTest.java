package io.majo.harness.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SessionProjectionTest {

    private static SessionService sessionService(Context root) {
        root.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        return root.get(SessionService.NAME);
    }

    private static SessionProjections projections(Context root, SessionService sessions) {
        root.plugin(new SessionProjectionsPlugin(), null).await().join();
        return root.get(SessionProjections.NAME);
    }

    @Test
    void parsesEveryKindIntoTypedRecords() {
        Map<String, Object> assistantFields = Map.of(
                SessionEvent.FIELD_TOOL_CALLS, List.of(Map.of(
                        SessionEvent.FIELD_TOOL_CALL_ID, "c1",
                        SessionEvent.FIELD_TOOL_NAME, "calc",
                        SessionEvent.FIELD_ARGUMENTS, "{\"expression\":\"1+2\"}")));
        SessionEvent assistant = new SessionEvent(3, SessionEventType.ASSISTANT_MESSAGE, 0, assistantFields);

        assertThat(TypedSessionEvent.of(
                new SessionEvent(1, SessionEventType.TURN_START, 0, Map.of())))
                .isEqualTo(new TypedSessionEvent.TurnStart());
        assertThat(TypedSessionEvent.of(
                new SessionEvent(2, SessionEventType.USER_MESSAGE, 0,
                        Map.of(SessionEvent.FIELD_CONTENT, "hi"))))
                .isEqualTo(new TypedSessionEvent.UserMessage("hi"));
        assertThat(TypedSessionEvent.of(assistant))
                .isEqualTo(new TypedSessionEvent.AssistantMessage(null, List.of(
                        new TypedSessionEvent.ToolCallEntry("c1", "calc", "{\"expression\":\"1+2\"}"))));
        assertThat(TypedSessionEvent.of(new SessionEvent(4, SessionEventType.TOOL_RESULT, 0, Map.of(
                SessionEvent.FIELD_TOOL_CALL_ID, "c1",
                SessionEvent.FIELD_TOOL_NAME, "calc",
                SessionEvent.FIELD_OK, true,
                SessionEvent.FIELD_CONTENT, "3"))))
                .isEqualTo(new TypedSessionEvent.ToolResult("c1", "calc", true, "3"));
        assertThat(TypedSessionEvent.of(new SessionEvent(5, SessionEventType.REQUEST_HEADER, 0, Map.of(
                SessionEvent.FIELD_MODEL, "mock",
                SessionEvent.FIELD_SYSTEM_PROMPT, "be helpful",
                SessionEvent.FIELD_TOOL_NAMES, List.of("calc")))))
                .isEqualTo(new TypedSessionEvent.RequestHeader("mock", "be helpful", List.of("calc")));

        // a malformed payload for its kind fails loudly when parsed
        SessionEvent malformed = new SessionEvent(6, SessionEventType.ASSISTANT_MESSAGE, 0,
                Map.of(SessionEvent.FIELD_TOOL_CALLS, "not-a-list"));
        assertThatThrownBy(() -> TypedSessionEvent.of(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolCalls");
    }

    @Test
    void unitsFoldLiveEventsAndReplayIdempotently() {
        Context root = Context.create();
        SessionService sessions = sessionService(root);
        SessionProjections projections = projections(root, sessions);
        CountingUnit unit = new CountingUnit();
        projections.register("counter", unit);

        String sessionId = sessions.createSession();
        sessions.append(sessionId, SessionEventType.TURN_START, Map.of());
        sessions.append(sessionId, SessionEventType.USER_MESSAGE, Map.of(SessionEvent.FIELD_CONTENT, "hi"));
        assertThat(unit.folded(sessionId)).isEqualTo(2);

        // replaying committed events is a no-op thanks to the sequence watermark
        projections.replay(sessionId);
        projections.replay(sessionId);
        assertThat(unit.folded(sessionId)).isEqualTo(2);
        assertThat(unit.kinds(sessionId)).containsExactly(
                SessionEventType.TURN_START, SessionEventType.USER_MESSAGE);

        // a fresh unit attached afterwards converges by replay alone
        CountingUnit late = new CountingUnit();
        projections.register("late", late);
        assertThat(late.folded(sessionId)).isZero();
        projections.replay(sessionId);
        assertThat(late.folded(sessionId)).isEqualTo(2);
        root.fiber().disposeAsync().join();
    }

    @Test
    void requiresAndUnregister() {
        Context root = Context.create();
        SessionService sessions = sessionService(root);
        SessionProjections projections = projections(root, sessions);

        CountingUnit unit = new CountingUnit();
        Disposable registration = projections.register("counter", unit);
        assertThat(projections.has("counter")).isTrue();
        CountingUnit registered = projections.require("counter");
        assertThat(registered).isSameAs(unit);
        assertThatThrownBy(() -> projections.register("counter", new CountingUnit()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> projections.require("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");

        registration.dispose();
        assertThat(projections.has("counter")).isFalse();
        assertThatThrownBy(() -> projections.require("counter")).isInstanceOf(IllegalStateException.class);
        root.fiber().disposeAsync().join();
    }

    @Test
    void unitUnregistersWithItsContributorPlugin() {
        Context root = Context.create();
        SessionService sessions = sessionService(root);
        root.plugin(new SessionProjectionsPlugin(), null).await().join();
        SessionProjections projections = root.get(SessionProjections.NAME);

        CountingUnit unit = new CountingUnit();
        java.util.Map<String, Object> inject = new java.util.HashMap<>();
        inject.put(SessionProjections.NAME, null);
        Plugin contributor = Plugin.object("contributor", inject, (ctx, config) -> {
            SessionProjections registry = ctx.get(SessionProjections.NAME);
            return registry.register("counter", unit);
        });
        io.jcordis.core.fiber.Fiber fiber = root.plugin(contributor, null).await().join();
        assertThat(projections.has("counter")).isTrue();

        fiber.disposeAsync().join();
        assertThat(projections.has("counter")).isFalse();
        root.fiber().disposeAsync().join();
    }

    /** Fold counter with typed read access, for assertions. */
    private static final class CountingUnit implements SessionProjection {
        private final Map<String, AtomicInteger> folded = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, List<SessionEventType>> kinds = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void onEvent(String sessionId, TypedSessionEvent event) {
            folded.computeIfAbsent(sessionId, ignored -> new AtomicInteger()).incrementAndGet();
            kinds.computeIfAbsent(sessionId, ignored -> new java.util.ArrayList<>())
                    .add(typeOf(event));
        }

        private static SessionEventType typeOf(TypedSessionEvent event) {
            return switch (event) {
                case TypedSessionEvent.TurnStart ignored -> SessionEventType.TURN_START;
                case TypedSessionEvent.TurnEnd ignored -> SessionEventType.TURN_END;
                case TypedSessionEvent.UserMessage ignored -> SessionEventType.USER_MESSAGE;
                case TypedSessionEvent.AssistantMessage ignored -> SessionEventType.ASSISTANT_MESSAGE;
                case TypedSessionEvent.ToolResult ignored -> SessionEventType.TOOL_RESULT;
                case TypedSessionEvent.RequestHeader ignored -> SessionEventType.REQUEST_HEADER;
            };
        }

        int folded(String sessionId) {
            AtomicInteger count = folded.get(sessionId);
            return count == null ? 0 : count.get();
        }

        List<SessionEventType> kinds(String sessionId) {
            List<SessionEventType> value = kinds.get(sessionId);
            return value == null ? List.of() : List.copyOf(value);
        }
    }
}
