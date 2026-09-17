package io.majo.harness.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.majo.harness.agent.loop.AgentLoopPlugin;
import io.majo.harness.llm.ChatModel;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.LLMService;
import io.majo.harness.llm.LLMServicePlugin;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionProjectionsPlugin;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.ToolsPlugin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Schedule semantics: durable records, follow-up delivery through the
 * agent-loop inbox, deletion, validation, and restart-safe re-arming (a fresh
 * runtime re-scans the session logs).
 */
class ScheduleTest {

    private static Context harness() {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new LLMServicePlugin(), Map.of("defaultModel", "model")).await().join();
        ctx.plugin(new AgentLoopPlugin(), null).await().join();
        return ctx;
    }

    @Test
    void dueScheduleIsDeliveredAsAFollowupTurn() throws Exception {
        Context ctx = harness();
        SessionService sessions = ctx.get(SessionService.NAME);
        LLMService llm = ctx.get(LLMService.NAME);
        llm.registerModel("model", new ChatModel() {
            @Override
            public ChatResponse complete(io.majo.harness.llm.ChatRequest request) {
                return ChatResponse.text("noted");
            }
        });
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        String sessionId = sessions.createSession();
        ScheduleService schedules = new ScheduleService(ctx, sessions, loop);

        schedules.create(sessionId, "check the nightly build", 1L, null, null);
        assertThat(schedules.list(sessionId)).hasSize(1);

        // the prompt arrives as its own turn (idle wake through the inbox)
        long deadline = System.currentTimeMillis() + 8000;
        boolean delivered = false;
        while (System.currentTimeMillis() < deadline && !delivered) {
            List<SessionEvent> events = sessions.events(sessionId);
            delivered = events.stream()
                    .anyMatch(event -> event.type() == SessionEventType.USER_MESSAGE
                            && "check the nightly build".equals(event.content()));
            if (!delivered) {
                Thread.sleep(50);
            }
        }
        assertThat(delivered).as("followup turn with the schedule prompt").isTrue();
        // one-shot: nothing left active after firing
        assertThat(schedules.list(sessionId)).isEmpty();
        schedules.close();
    }

    @Test
    void deletionCancelsBeforeFiring() throws Exception {
        Context ctx = harness();
        SessionService sessions = ctx.get(SessionService.NAME);
        String sessionId = sessions.createSession();
        ScheduleService schedules = new ScheduleService(ctx, sessions, null);

        ScheduleService.Schedule schedule = schedules.create(sessionId, "later", 60L, null, null);
        assertThat(schedules.delete(sessionId, schedule.id)).isTrue();
        assertThat(schedules.delete(sessionId, schedule.id)).isFalse();
        assertThat(schedules.list(sessionId)).isEmpty();

        // durable cancellation rides the log
        long cancels = sessions.events(sessionId).stream()
                .filter(event -> event.type() == SessionEventType.SCHEDULE_SET)
                .filter(event -> Boolean.parseBoolean(String.valueOf(
                        event.fields().get(io.majo.harness.session.SessionEvent.FIELD_CANCELLED))))
                .count();
        assertThat(cancels).isEqualTo(1);
        schedules.close();
    }

    @Test
    void freshRuntimeRescansDurableSchedules() {
        Context ctx = harness();
        SessionService sessions = ctx.get(SessionService.NAME);
        String sessionId = sessions.createSession();
        ScheduleService first = new ScheduleService(ctx, sessions, null);
        ScheduleService.Schedule created = first.create(
                sessionId, "standup notes", 3600L, null, null);
        first.close();
        // restart simulation: rescan() wipes the in-memory maps and re-folds
        // the durable logs (a fresh runtime would do exactly this on mount)
        first.rescan();

        List<ScheduleService.Schedule> active = first.list(sessionId);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).id).isEqualTo(created.id);
        assertThat(active.get(0).prompt).isEqualTo("standup notes");
        // repeat-style schedule's due time advanced past now on the rescan
        assertThat(active.get(0).dueAtMs).isGreaterThan(System.currentTimeMillis() - 1000);
        first.close();
    }

    @Test
    void creationValidatesTimingShapes() {
        Context ctx = harness();
        SessionService sessions = ctx.get(SessionService.NAME);
        String sessionId = sessions.createSession();
        ScheduleService schedules = new ScheduleService(ctx, sessions, null);

        assertThatThrownBy(() -> schedules.create(sessionId, "x", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> schedules.create(sessionId, "x", 5L, 0L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> schedules.create(sessionId, "x", null, null, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every_seconds must be >= 300");
        assertThatThrownBy(() -> schedules.create(sessionId, " ", 5L, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt");
        schedules.close();
    }
}
