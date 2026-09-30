package io.majo.harness.goal;

import io.jcordis.core.context.Context;
import io.majo.harness.goal.GoalService.Goal;
import io.majo.harness.goal.GoalService.Ref;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The goal domain: CAS stale-write protection, legal phase transitions,
 * the clear tombstone with id non-reuse, restart disarm, and the derived
 * round count.
 */
class GoalDomainTest {

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

    @Test
    void casStaleRevisionFailsLoudly() {
        Context ctx = boot();
        SessionService sessions = ctx.get(SessionService.NAME);
        String sessionId = sessions.createSession();
        GoalService goals = new GoalService(ctx, null, sessions,
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME));

        Goal goal = goals.create(sessionId, "ship the release", null);
        assertThatThrownBy(() -> goals.edit(sessionId, new Ref(goal.goalId(), 99), "new", null))
                .isInstanceOf(GoalException.class)
                .hasMessageContaining("stale revision");
        // the legal CAS edit lands and bumps the revision
        Goal edited = goals.edit(sessionId, goal.ref(), "ship the release v2", 16L);
        assertThat(edited.revision()).isEqualTo(2);
        assertThat(edited.objective()).isEqualTo("ship the release v2");
        assertThat(edited.maxRounds()).isEqualTo(16);
        // the phase survived the edit (immutable)
        assertThat(edited.phase()).isEqualTo(GoalService.Phase.ACTIVE);
    }

    @Test
    void phaseTransitionsAreLegalOnlyAsDesigned() {
        Context ctx = boot();
        SessionService sessions = ctx.get(SessionService.NAME);
        String sessionId = sessions.createSession();
        GoalService goals = new GoalService(ctx, null, sessions,
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME));

        Goal goal = goals.create(sessionId, "obj", null);
        // block only from active
        Goal blocked = goals.block(sessionId, goal.ref(), "waiting-on-creds", "need the key");
        assertThat(blocked.phase()).isEqualTo(GoalService.Phase.BLOCKED);
        assertThatThrownBy(() -> goals.block(sessionId, blocked.ref(), "again", "nope"))
                .isInstanceOf(GoalException.class)
                .hasMessageContaining("only an active goal");
        // resume from blocked re-arms (fresh instance check below)
        Goal resumed = goals.resume(sessionId, blocked.ref());
        assertThat(resumed.phase()).isEqualTo(GoalService.Phase.ACTIVE);
        // complete is terminal
        Goal done = goals.complete(sessionId, resumed.ref());
        assertThat(done.phase()).isEqualTo(GoalService.Phase.COMPLETE);
        assertThatThrownBy(() -> goals.complete(sessionId, done.ref()))
                .isInstanceOf(GoalException.class)
                .hasMessageContaining("already complete");
    }

    @Test
    void clearWritesTombstoneAndIdsAreNeverReused() {
        Context ctx = boot();
        SessionService sessions = ctx.get(SessionService.NAME);
        String sessionId = sessions.createSession();
        GoalService goals = new GoalService(ctx, null, sessions,
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME));

        Goal first = goals.create(sessionId, "first", null);
        goals.clear(sessionId);
        assertThat(goals.get(sessionId)).isNull();
        // the tombstone is durable
        assertThat(sessions.events(sessionId))
                .filteredOn(event -> event.type() == SessionEventType.GOAL_CHANGE)
                .last()
                .satisfies(event -> assertThat(event.fields())
                        .containsEntry(SessionEvent.FIELD_OPERATION, "clear"));
        // a new create gets a fresh id
        Goal second = goals.create(sessionId, "second", null);
        assertThat(second.goalId()).isNotEqualTo(first.goalId());
    }

    @Test
    void aFreshRuntimeRestoresTheGoalDisarmed() {
        Context ctx = boot();
        SessionService sessions = ctx.get(SessionService.NAME);
        String sessionId = sessions.createSession();
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        GoalService first = new GoalService(ctx, null, sessions, loop);
        first.create(sessionId, "keep going", null);
        assertThat(first.get(sessionId)).isNotNull();

        // restart simulation: a fresh service over the same store (the loop's
        // goal listeners are ctx-level; use a raw instance without re-drive)
        GoalService restarted = new GoalService(Context.create(), null, sessions, loop);
        assertThat(restarted.get(sessionId)).isNotNull();
        assertThat(restarted.get(sessionId).phase()).isEqualTo(GoalService.Phase.ACTIVE);
        // disarm = maybeDrive offers nothing: no goal-produced messages appear
        restarted.maybeDrive(sessionId);
        long goalMessages = sessions.events(sessionId).stream()
                .filter(event -> event.type() == SessionEventType.USER_MESSAGE)
                .filter(event -> "goal".equals(event.fields()
                        .get(SessionEvent.FIELD_PRODUCER)))
                .count();
        assertThat(goalMessages).isZero();
    }

    @Test
    void hostPauseAbortsTheRunningTurnAndPauses() throws Exception {
        Context ctx = boot();
        SessionService sessions = ctx.get(SessionService.NAME);
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        ((io.majo.harness.llm.LLMService) ctx.get(io.majo.harness.llm.LLMService.NAME)).registerModel("model", request -> {
            try {
                // park the model call so the turn is running when paused; the
                // response is a tool round so the loop's pre-dispatch abort
                // check fires (a final answer legitimately completes)
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return io.majo.harness.llm.ChatResponse.toolCalls(java.util.List.of(
                    io.majo.harness.tools.ToolCall.of("echo", "{}")));
        });
        ((io.majo.harness.tools.ToolRegistry) ctx.get(io.majo.harness.tools.ToolRegistry.NAME)).register(new io.majo.harness.tools.Tool() {
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

        Goal goal = goals.create(sessionId, "long objective", null);
        // drive one round: armed + idle → the loop queues the round prompt
        goals.maybeDrive(sessionId);
        long deadline = System.currentTimeMillis() + 5000;
        // wait until the round turn is IN FLIGHT (its request header logged),
        // then the host pause aborts it mid-flight
        boolean inFlight = false;
        while (System.currentTimeMillis() < deadline) {
            inFlight = sessions.events(sessionId).stream()
                    .anyMatch(event -> event.type() == SessionEventType.REQUEST_HEADER);
            if (inFlight) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(inFlight).as("goal round turn in flight").isTrue();
        // host pause: aborts + pauses
        goals.pause(sessionId, goal.ref(), true);
        assertThat(goals.get(sessionId).phase()).isEqualTo(GoalService.Phase.PAUSED);
        deadline = System.currentTimeMillis() + 5000;
        boolean aborted = false;
        while (System.currentTimeMillis() < deadline && !aborted) {
            aborted = sessions.events(sessionId).stream()
                    .anyMatch(event -> event.type() == SessionEventType.TURN_END
                            && "aborted".equals(event.fields()
                                    .get(SessionEvent.FIELD_REASON)));
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(aborted).as("aborted turn end").isTrue();
    }
}
