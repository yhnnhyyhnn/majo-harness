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
                            && "check the nightly build".equals(event.content())
                            || (event.content() != null && event.content().endsWith("check the nightly build")));
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

    @Test
    void cronSchedulesCarryTheirExpressionAndRearmAfterFiring() {
        Context ctx = harness();
        SessionService sessions = ctx.get(SessionService.NAME);
        String sessionId = sessions.createSession();
        ScheduleService schedules = new ScheduleService(ctx, sessions, null);

        ScheduleService.Schedule created = schedules.create(
                sessionId, "cron check", null, null, null, "30 9 * * 1-5", "UTC");
        assertThat(created.cron).isEqualTo("30 9 * * 1-5");
        assertThat(created.timezone).isEqualTo("UTC");
        assertThat(created.intervalSeconds).isZero();
        assertThat(created.dueAtMs).isGreaterThan(System.currentTimeMillis());

        // the durable record carries the expression so a restart restores it
        boolean loggedWithCron = sessions.events(sessionId).stream()
                .filter(event -> event.type() == SessionEventType.SCHEDULE_SET)
                .anyMatch(event -> "30 9 * * 1-5".equals(
                        event.fields().get(SessionEvent.FIELD_CRON))
                        && "UTC".equals(event.fields().get(SessionEvent.FIELD_TIMEZONE)));
        assertThat(loggedWithCron).isTrue();

        // firing recomputes the next occurrence from the expression instead
        // of advancing a fixed interval: simulate the timer having reached the
        // due time, then the next occurrence must land in the future
        long firedAt = System.currentTimeMillis();
        created.dueAtMs = firedAt - 60_000;
        schedules.fire(sessionId, created);
        assertThat(created.dueAtMs).isGreaterThan(firedAt);
        assertThat(schedules.list(sessionId)).hasSize(1);

        // restart simulation: the rescan restores the cron schedule from the log
        schedules.rescan();
        ScheduleService.Schedule restored = schedules.list(sessionId).get(0);
        assertThat(restored.cron).isEqualTo("30 9 * * 1-5");
        assertThat(restored.dueAtMs).isGreaterThan(System.currentTimeMillis());
        schedules.close();
    }

    @Test
    void cronSchedulesValidateExpressionsAndTimezones() {
        Context ctx = harness();
        SessionService sessions = ctx.get(SessionService.NAME);
        String sessionId = sessions.createSession();
        ScheduleService schedules = new ScheduleService(ctx, sessions, null);

        assertThatThrownBy(() -> schedules.create(
                sessionId, "x", null, null, null, "0 9 * * * 1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 fields");
        assertThatThrownBy(() -> schedules.create(
                sessionId, "x", null, null, null, "0 9 * * *", "Mars/Olympus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mars/Olympus");
        schedules.close();
    }

    /** dsh 0.2.1 parity: delegated children cannot create or edit reminders. */
    @Test
    void reminderToolsRefuseDelegatedChildren() {
        Context ctx = harness();
        ctx.plugin(new SchedulePlugin(), null).await().join();
        io.majo.harness.tools.ToolRegistry tools = ctx.get(io.majo.harness.tools.ToolRegistry.NAME);
        String sessionId = ((SessionService) ctx.get(SessionService.NAME)).createSession();

        // root depth: allowed (session-bound, no delegation)
        io.majo.harness.tools.ToolResult created =
                io.majo.harness.interaction.InteractionContext.runSession(sessionId, () ->
                        tools.execute(io.majo.harness.tools.ToolCall.of("schedule_create",
                                "{\"prompt\":\"standup\",\"after_seconds\":3600}")));
        assertThat(created.ok()).isTrue();

        // a delegated child (depth 1): refused at the tool layer
        io.majo.harness.interaction.InteractionContext.runSession(sessionId, () ->
                io.majo.harness.interaction.InteractionContext.run("subagent-child", false, () -> {
                    io.majo.harness.tools.ToolResult refused = tools.execute(
                            io.majo.harness.tools.ToolCall.of("schedule_create",
                                    "{\"prompt\":\"child\",\"after_seconds\":3600}"));
                    assertThat(refused.ok()).isFalse();
                    assertThat(refused.error())
                            .contains("delegated subagent cannot use reminders");
                    io.majo.harness.tools.ToolResult edited = tools.execute(
                            io.majo.harness.tools.ToolCall.of("schedule_update",
                                    "{\"id\":\"sched-1\",\"prompt\":\"x\"}"));
                    assertThat(edited.ok()).isFalse();
                    return null;
                }));
        ctx.fiber().disposeAsync().join();
    }

    /** The delivered reminder is framed as a scheduled user message (dsh parity). */
    @Test
    void deliveredRemindersCarryTheScheduledMessageFraming() throws Exception {
        Context ctx = harness();
        ctx.plugin(new SchedulePlugin(), null).await().join();
        SessionService sessions = ctx.get(SessionService.NAME);
        ScheduleService schedules = ctx.get(ScheduleService.NAME);
        ((io.majo.harness.llm.LLMService) ctx.get(io.majo.harness.llm.LLMService.NAME))
                .registerModel("model", request -> io.majo.harness.llm.ChatResponse.text("noted"));
        String sessionId = sessions.createSession();

        ScheduleService.Schedule created = schedules.create(
                sessionId, "check the nightly build", 1L, null, null);
        created.dueAtMs = System.currentTimeMillis() - 1000; // due now
        schedules.fire(sessionId, created);

        long deadline = System.currentTimeMillis() + 5000;
        boolean framed = false;
        while (System.currentTimeMillis() < deadline && !framed) {
            framed = sessions.events(sessionId).stream()
                    .anyMatch(event -> event.type() == SessionEventType.USER_MESSAGE
                            && event.content() != null
                            && event.content().startsWith("[SCHEDULE REMINDER]")
                            && event.content().contains("scheduled message from the user")
                            && event.content().endsWith("check the nightly build"));
            java.util.concurrent.TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(framed).as("framed reminder delivery").isTrue();
        ctx.fiber().disposeAsync().join();
    }
}
