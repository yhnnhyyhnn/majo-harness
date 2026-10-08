package io.majo.harness.goal;

import io.jcordis.core.context.Context;
import io.majo.harness.llm.ChatModel;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.LLMService;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Goal round withdrawal (dsh goal-round-driver 9a8d21dfe7 parity): a
 * cancelled turn withdraws its queued round and DISARMS the goal — the human
 * regains control after an interruption — and a round stands down at
 * admission when other turn work is queued (the human outranks the goal at
 * every point, not just at offer time).
 */
class GoalWithdrawTest {

    private static Context boot() {
        Context ctx = Context.create();
        ctx.plugin(new io.majo.harness.session.SessionPlugin(), Map.of("store", "memory"))
                .await().join();
        ctx.plugin(new io.majo.harness.session.SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new io.majo.harness.tools.ToolsPlugin(), null).await().join();
        ctx.plugin(new io.majo.harness.llm.LLMServicePlugin(), Map.of("defaultModel", "model"))
                .await().join();
        ctx.plugin(new io.majo.harness.agent.loop.AgentLoopPlugin(), null).await().join();
        return ctx;
    }

    private static long countGoalMessages(SessionService sessions, String sessionId) {
        return sessions.events(sessionId).stream()
                .filter(event -> event.type() == SessionEventType.USER_MESSAGE)
                .filter(event -> "goal".equals(event.fields()
                        .get(SessionEvent.FIELD_PRODUCER)))
                .count();
    }

    @Test
    void abortedTurnDisarmsTheGoalAndStopsTheDriveLoop() throws Exception {
        Context ctx = boot();
        SessionService sessions = ctx.get(SessionService.NAME);
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        CountDownLatch firstCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ((LLMService) ctx.get(LLMService.NAME)).registerModel("model", (ChatModel) request -> {
            firstCall.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // a tool round: the loop's pre-dispatch abort check fires after
            // the model returns (a final answer legitimately completes)
            return ChatResponse.toolCalls(java.util.List.of(
                    io.majo.harness.tools.ToolCall.of("echo", "{}")));
        });
        ((io.majo.harness.tools.ToolRegistry) ctx.get(io.majo.harness.tools.ToolRegistry.NAME))
                .register(new io.majo.harness.tools.Tool() {
                    @Override
                    public io.majo.harness.tools.ToolSpec spec() {
                        return io.majo.harness.tools.ToolSpec.of("echo", "echoes");
                    }

                    @Override
                    public io.majo.harness.tools.ToolResult execute(io.majo.harness.tools.ToolCall call) {
                        return io.majo.harness.tools.ToolResult.ok("echo");
                    }
                });
        GoalService goals = new GoalService(ctx, null, sessions, loop);

        String sessionId = sessions.createSession();
        goals.create(sessionId, "long running objective", null);
        // the self-drive parks the round inside the model call
        assertThat(firstCall.await(5, TimeUnit.SECONDS)).isTrue();

        loop.abort(sessionId);
        release.countDown();
        // the turn closes aborted; the goal DISARMS (dsh: the human regains
        // control after an interruption — no immediate re-offer)
        long deadline = System.currentTimeMillis() + 5000;
        boolean disarmed = false;
        while (System.currentTimeMillis() < deadline && !disarmed) {
            SessionEvent end = sessions.events(sessionId).stream()
                    .filter(event -> event.type() == SessionEventType.TURN_END)
                    .reduce((a, b) -> b).orElse(null);
            disarmed = end != null && "aborted".equals(
                    end.fields().get(io.majo.harness.session.SessionEvent.FIELD_REASON));
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(disarmed).as("aborted turn end").isTrue();

        // after the abort, NO new goal round appears (disarmed): wait well
        // past the old re-offer window and count again
        long atAbort = countGoalMessages(sessions, sessionId);
        TimeUnit.MILLISECONDS.sleep(600);
        assertThat(countGoalMessages(sessions, sessionId)).isEqualTo(atAbort);
        // and the queued-round probe agrees: nothing goal-produced is queued
        assertThat(loop.hasQueuedGoalRound(sessionId)).isFalse();
    }

    @Test
    void admissionStandsDownWhenOtherTurnWorkIsQueued() throws Exception {
        Context ctx = boot();
        SessionService sessions = ctx.get(SessionService.NAME);
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        // round 1 parks (driver busy), round 2 queues behind it, then a human
        // prompt queues behind round 2: admitting round 2 must stand down so
        // the human prompt is not stuck behind a full goal round
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ((LLMService) ctx.get(LLMService.NAME)).registerModel("model", (ChatModel) request -> {
            parked.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ChatResponse.text("progress");
        });
        GoalService goals = new GoalService(ctx, null, sessions, loop);
        String sessionId = sessions.createSession();
        GoalService.Goal goal = goals.create(sessionId, "objective", null);
        assertThat(parked.await(5, TimeUnit.SECONDS)).isTrue(); // round 1 running

        goals.maybeDrive(sessionId); // offers round 2 (queued; driver busy)
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && !loop.hasQueuedGoalRound(sessionId)) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(loop.hasQueuedGoalRound(sessionId)).isTrue();

        loop.followup(sessionId, "human comes first"); // queued behind round 2

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        ctx.waterfall(null, io.majo.harness.agent.loop.AgentLoopEvents.USER_SUBMIT,
                                new Object[] {sessionId,
                                        GoalService.renderRoundPrompt(goal, 2)},
                                args -> args[1]))
                .isInstanceOf(GoalException.class)
                .hasMessageContaining("stands down");

        release.countDown(); // round 1 completes; the queue sorts itself out
    }
}
