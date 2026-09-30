package io.majo.harness.goal;

import io.jcordis.core.context.Context;
import io.majo.harness.llm.ChatModel;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.LLMService;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The round driver: an armed goal queues its exact rendered round prompt as a
 * goal-produced user message; the admission fence rejects when the goal state
 * moved; the round budget blocks with round-limit; the driver stands down
 * while other inbox work is queued.
 */
class GoalDriverTest {

    private static Context boot() {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new io.majo.harness.session.SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new io.majo.harness.tools.ToolsPlugin(), null).await().join();
        ctx.plugin(new io.majo.harness.llm.LLMServicePlugin(), Map.of("defaultModel", "model"))
                .await().join();
        ctx.plugin(new io.majo.harness.agent.loop.AgentLoopPlugin(), null).await().join();
        return ctx;
    }

    private static void await(Boolean condition, SessionService sessions, String sessionId,
            java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(done.getAsBoolean()).as("condition: " + condition).isTrue();
    }

    @Test
    void armedGoalDrivesRoundsWithTheExactPromptAndRoundMetadata() throws Exception {
        Context ctx = boot();
        SessionService sessions = ctx.get(SessionService.NAME);
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        AtomicInteger rounds = new AtomicInteger();
        ((LLMService) ctx.get(LLMService.NAME)).registerModel("model", (ChatModel) request -> {
            rounds.incrementAndGet();
            return ChatResponse.text("progress " + rounds.get());
        });
        GoalService goals = new GoalService(ctx, null, sessions, loop);
        String sessionId = sessions.createSession();
        GoalService.Goal goal = goals.create(sessionId, "write the docs", null);

        goals.maybeDrive(sessionId);
        await(null, sessions, sessionId, () ->
                sessions.events(sessionId).stream().anyMatch(event ->
                        event.type() == SessionEventType.USER_MESSAGE
                                && "goal".equals(event.fields()
                                        .get(SessionEvent.FIELD_PRODUCER))));
        // the round message carries goal metadata and the exact rendered prompt
        SessionEvent round = sessions.events(sessionId).stream()
                .filter(event -> event.type() == SessionEventType.USER_MESSAGE
                        && "goal".equals(event.fields().get(SessionEvent.FIELD_PRODUCER)))
                .findFirst().orElseThrow();
        assertThat(round.fields().get(SessionEvent.FIELD_GOAL_ID)).isEqualTo(goal.goalId());
        assertThat(round.fields().get(SessionEvent.FIELD_ROUND)).isEqualTo(1L);
        assertThat(round.content())
                .isEqualTo(GoalService.renderRoundPrompt(goal, 1));
        assertThat(round.content()).contains("Round: 1/256");
    }

    @Test
    void roundBudgetBlocksTheGoalWithRoundLimit() throws Exception {
        Context ctx = boot();
        SessionService sessions = ctx.get(SessionService.NAME);
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        ((LLMService) ctx.get(LLMService.NAME)).registerModel("model",
                (ChatModel) request -> ChatResponse.text("ok"));
        GoalService goals = new GoalService(ctx, null, sessions, loop);
        String sessionId = sessions.createSession();
        GoalService.Goal goal = goals.create(sessionId, "tiny budget", 2L);

        // simulate two admitted rounds directly in the log
        sessions.append(sessionId, SessionEventType.TURN_START, Map.of());
        sessions.append(sessionId, SessionEventType.USER_MESSAGE, Map.of(
                SessionEvent.FIELD_CONTENT,
                GoalService.renderRoundPrompt(goal, 1),
                SessionEvent.FIELD_PRODUCER, "goal",
                SessionEvent.FIELD_GOAL_ID, goal.goalId(),
                SessionEvent.FIELD_GOAL_REVISION, goal.revision(),
                SessionEvent.FIELD_ROUND, 1));
        sessions.append(sessionId, SessionEventType.TURN_START, Map.of());
        sessions.append(sessionId, SessionEventType.USER_MESSAGE, Map.of(
                SessionEvent.FIELD_CONTENT,
                GoalService.renderRoundPrompt(goal, 2),
                SessionEvent.FIELD_PRODUCER, "goal",
                SessionEvent.FIELD_GOAL_ID, goal.goalId(),
                SessionEvent.FIELD_GOAL_REVISION, goal.revision(),
                SessionEvent.FIELD_ROUND, 2));

        goals.maybeDrive(sessionId);
        // the block may land on this thread or the driver thread (the create
        // self-drive race) — poll for it
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && goals.get(sessionId).phase() != GoalService.Phase.BLOCKED) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(goals.get(sessionId).phase()).isEqualTo(GoalService.Phase.BLOCKED);
        assertThat(goals.get(sessionId).blockedCode()).isEqualTo("round-limit");
    }

    @Test
    void staleReservationIsRejectedBeforeAnythingLogs() throws Exception {
        Context ctx = boot();
        SessionService sessions = ctx.get(SessionService.NAME);
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        ((LLMService) ctx.get(LLMService.NAME)).registerModel("model",
                (ChatModel) request -> ChatResponse.text("ok"));
        // a raw (loop-less) service: maybeDrive registers the attempt without
        // handing it to the loop, so the admission check is deterministic
        // (listeners still register on the boot ctx for the manual waterfall)
        GoalService goals = new GoalService(ctx, null, sessions, null);
        String sessionId = sessions.createSession();
        GoalService.Goal goal = goals.create(sessionId, "obj", null);
        goals.maybeDrive(sessionId);
        // the attempt is pending; the goal moved underneath (host clear+create)
        goals.clear(sessionId);
        GoalService.Goal replacement = goals.create(sessionId, "obj", null);

        int eventsBefore = sessions.events(sessionId).size();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        ctx.waterfall(null, io.majo.harness.agent.loop.AgentLoopEvents.USER_SUBMIT,
                                new Object[] {sessionId,
                                        GoalService.renderRoundPrompt(goal, 1)},
                                args -> args[1]))
                .isInstanceOf(GoalException.class)
                .hasMessageContaining("stale");
        // nothing was logged by the rejection (no TURN_START opened)
        assertThat(sessions.events(sessionId)).hasSize(eventsBefore);
        // and the fresh goal is untouched
        assertThat(goals.get(sessionId).goalId()).isEqualTo(replacement.goalId());
    }

    @Test
    void competingInboxWorkStandsTheDriverDown() throws Exception {
        Context ctx = boot();
        SessionService sessions = ctx.get(SessionService.NAME);
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        ((LLMService) ctx.get(LLMService.NAME)).registerModel("model",
                (ChatModel) request -> ChatResponse.text("ok"));
        GoalService goals = new GoalService(ctx, null, sessions, loop);
        String sessionId = sessions.createSession();
        goals.create(sessionId, "obj", null);

        // a human followup is queued: the goal stands down (no round offered)
        loop.followup(sessionId, "human comes first");
        goals.maybeDrive(sessionId);
        assertThat(sessions.events(sessionId).stream()
                .noneMatch(event -> "goal".equals(
                        event.fields().get(SessionEvent.FIELD_PRODUCER))))
                .isTrue();
        // the goal stays armed for a later idle re-evaluation
        assertThat(goals.get(sessionId).phase()).isEqualTo(GoalService.Phase.ACTIVE);
    }
}
