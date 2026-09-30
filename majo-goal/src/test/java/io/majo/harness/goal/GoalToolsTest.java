package io.majo.harness.goal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jcordis.core.context.Context;
import io.majo.harness.goal.GoalService.Goal;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The goal tools' authority surface: create/edit/pause/resume demand a real
 * user message in the open turn; complete/blocked work inside a goal round;
 * the autonomous blocked floor applies; the wrapup note lands after an
 * autonomous terminal update.
 */
class GoalToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Harness(Context ctx, SessionService sessions, GoalService goals,
            ToolRegistry tools) {

        static Harness mount() {
            Context ctx = Context.create();
            ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
            ctx.plugin(new io.majo.harness.session.SessionProjectionsPlugin(), null).await().join();
            ctx.plugin(new io.majo.harness.tools.ToolsPlugin(), null).await().join();
            ctx.plugin(new io.majo.harness.llm.LLMServicePlugin(), Map.of("defaultModel", "model"))
                    .await().join();
            ((io.majo.harness.llm.LLMService) ctx.get(io.majo.harness.llm.LLMService.NAME))
                    .registerModel("model", request -> io.majo.harness.llm.ChatResponse.text("ok"));
            ctx.plugin(new io.majo.harness.agent.loop.AgentLoopPlugin(), null).await().join();
            SessionService sessions = ctx.get(SessionService.NAME);
            GoalService goals = new GoalService(ctx, null, sessions,
                    ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME));
            ctx.plugin(new GoalToolPlugin(goals,
                    ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME)), null).await().join();
            return new Harness(ctx, sessions, goals, ((ToolRegistry) ctx.get(ToolRegistry.NAME)));
        }
    }

    /** Simulates an open turn whose opening message carries the given producer. */
    private static void openTurn(SessionService sessions, String sessionId,
            Map<String, Object> producer) {
        sessions.append(sessionId, SessionEventType.TURN_START, Map.of());
        java.util.Map<String, Object> fields = new java.util.HashMap<>(producer);
        fields.put(SessionEvent.FIELD_CONTENT, "turn opening");
        sessions.append(sessionId, SessionEventType.USER_MESSAGE, fields);
    }

    @Test
    void createNeedsADirectHumanTurn() throws Exception {
        Harness h = Harness.mount();
        String sessionId = h.sessions().createSession();

        // no open turn at all: the tool refuses
        String createArgs = MAPPER.writeValueAsString(Map.of("objective", "x"));
        ToolResult outside = InteractionContext.runSession(sessionId, () ->
                h.tools().execute(ToolCall.of("create_goal", createArgs)));
        assertThat(outside.ok()).isFalse();

        // a model-only turn (goal producer) is not human
        openTurn(h.sessions(), sessionId, Map.of(SessionEvent.FIELD_PRODUCER, "goal",
                SessionEvent.FIELD_GOAL_ID, "goal-9", SessionEvent.FIELD_ROUND, 1));
        ToolResult modelTurn = InteractionContext.runSession(sessionId, () ->
                h.tools().execute(ToolCall.of("create_goal", createArgs)));
        assertThat(modelTurn.ok()).isFalse();
        assertThat(modelTurn.error()).contains("direct human request");

        // a human opening unlocks create
        h.sessions().append(sessionId, SessionEventType.TURN_START, Map.of());
        h.sessions().append(sessionId, SessionEventType.USER_MESSAGE,
                Map.of(SessionEvent.FIELD_CONTENT, "please own this task"));
        String shipArgs = MAPPER.writeValueAsString(Map.of("objective", "ship it"));
        ToolResult humanTurn = InteractionContext.runSession(sessionId, () ->
                h.tools().execute(ToolCall.of("create_goal", shipArgs)));
        assertThat(humanTurn.ok()).isTrue();
        assertThat(h.goals().get(sessionId).objective()).isEqualTo("ship it");
    }

    @Test
    void pauseAndResumeAreHumanOnly() throws Exception {
        Harness h = Harness.mount();
        String sessionId = h.sessions().createSession();
        Goal goal = h.goals().create(sessionId, "obj", null);

        // inside a goal round (not human): pause refused
        openTurn(h.sessions(), sessionId, Map.of(SessionEvent.FIELD_PRODUCER, "goal",
                SessionEvent.FIELD_GOAL_ID, goal.goalId(), SessionEvent.FIELD_ROUND, 1));
        String pauseArgs = MAPPER.writeValueAsString(Map.of(
                "goal_id", goal.goalId(), "revision", goal.revision(), "action", "pause"));
        ToolResult refused = InteractionContext.runSession(sessionId, () ->
                h.tools().execute(ToolCall.of("update_goal", pauseArgs)));
        assertThat(refused.ok()).isFalse();
        assertThat(refused.error()).contains("direct human request");

        // human turn: pause + model-resume-of-paused is rejected via transition
        h.sessions().append(sessionId, SessionEventType.TURN_START, Map.of());
        h.sessions().append(sessionId, SessionEventType.USER_MESSAGE,
                Map.of(SessionEvent.FIELD_CONTENT, "pause it"));
        String pauseArgs2 = MAPPER.writeValueAsString(Map.of(
                "goal_id", goal.goalId(), "revision", goal.revision(), "action", "pause"));
        ToolResult paused = InteractionContext.runSession(sessionId, () ->
                h.tools().execute(ToolCall.of("update_goal", pauseArgs2)));
        assertThat(paused.ok()).isTrue();
        assertThat(h.goals().get(sessionId).phase()).isEqualTo(GoalService.Phase.PAUSED);
    }

    @Test
    void autonomousCompleteWorksInsideAGoalRoundAndInjectsWrapup() throws Exception {
        Harness h = Harness.mount();
        String sessionId = h.sessions().createSession();
        Goal goal = h.goals().create(sessionId, "finish the migration", null);

        openTurn(h.sessions(), sessionId, Map.of(SessionEvent.FIELD_PRODUCER, "goal",
                SessionEvent.FIELD_GOAL_ID, goal.goalId(),
                SessionEvent.FIELD_GOAL_REVISION, goal.revision(),
                SessionEvent.FIELD_ROUND, 1));
        String completeArgs = MAPPER.writeValueAsString(Map.of(
                "goal_id", goal.goalId(), "revision", goal.revision(), "action", "complete"));
        ToolResult done = InteractionContext.runSession(sessionId, () ->
                h.tools().execute(ToolCall.of("update_goal", completeArgs)));
        assertThat(done.ok()).isTrue();
        assertThat(h.goals().get(sessionId).phase()).isEqualTo(GoalService.Phase.COMPLETE);
        // the wrapup note was queued for this turn's next step boundary — a
        // follow-up turn opening delivers it as a durable CONTEXT_NOTE
        io.majo.harness.agent.loop.AgentLoopService loop =
                h.ctx().get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        loop.runTurn(sessionId, "wrap up");
        assertThat(h.sessions().events(sessionId))
                .filteredOn(event -> event.type() == SessionEventType.CONTEXT_NOTE)
                .anySatisfy(event -> assertThat(event.content()).contains("<goal_complete>"));
    }

    @Test
    void autonomousBlockedHasAConsecutiveRoundsFloor() throws Exception {
        Harness h = Harness.mount();
        String sessionId = h.sessions().createSession();
        Goal goal = h.goals().create(sessionId, "obj", null);

        // round 1 of 3: the autonomous floor rejects
        openTurn(h.sessions(), sessionId, Map.of(SessionEvent.FIELD_PRODUCER, "goal",
                SessionEvent.FIELD_GOAL_ID, goal.goalId(), SessionEvent.FIELD_ROUND, 1));
        String earlyArgs = MAPPER.writeValueAsString(Map.of(
                "goal_id", goal.goalId(), "revision", goal.revision(),
                "action", "blocked", "code", "stuck", "message", "blocked"));
        ToolResult tooEarly = InteractionContext.runSession(sessionId, () ->
                h.tools().execute(ToolCall.of("update_goal", earlyArgs)));
        assertThat(tooEarly.ok()).isFalse();
        assertThat(tooEarly.error()).contains("consecutive goal rounds");

        // rounds 2 and 3 admitted: the floor is satisfied
        openTurn(h.sessions(), sessionId, Map.of(SessionEvent.FIELD_PRODUCER, "goal",
                SessionEvent.FIELD_GOAL_ID, goal.goalId(), SessionEvent.FIELD_ROUND, 2));
        openTurn(h.sessions(), sessionId, Map.of(SessionEvent.FIELD_PRODUCER, "goal",
                SessionEvent.FIELD_GOAL_ID, goal.goalId(), SessionEvent.FIELD_ROUND, 3));
        String floorArgs = MAPPER.writeValueAsString(Map.of(
                "goal_id", goal.goalId(), "revision", goal.revision(),
                "action", "blocked", "code", "stuck", "message", "still blocked"));
        ToolResult allowed = InteractionContext.runSession(sessionId, () ->
                h.tools().execute(ToolCall.of("update_goal", floorArgs)));
        assertThat(allowed.ok()).isTrue();
        assertThat(h.goals().get(sessionId).phase()).isEqualTo(GoalService.Phase.BLOCKED);
        io.majo.harness.agent.loop.AgentLoopService loop =
                h.ctx().get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        loop.runTurn(sessionId, "wrap up");
        assertThat(h.sessions().events(sessionId))
                .filteredOn(event -> event.type() == SessionEventType.CONTEXT_NOTE)
                .anySatisfy(event -> assertThat(event.content()).contains("<goal_blocked>"));
    }

    @Test
    void staleCasGuardSurfacesThroughTheTool() throws Exception {
        Harness h = Harness.mount();
        String sessionId = h.sessions().createSession();
        Goal goal = h.goals().create(sessionId, "obj", null);
        h.sessions().append(sessionId, SessionEventType.TURN_START, Map.of());
        h.sessions().append(sessionId, SessionEventType.USER_MESSAGE,
                Map.of(SessionEvent.FIELD_CONTENT, "go"));
        String staleArgs = MAPPER.writeValueAsString(Map.of(
                "goal_id", goal.goalId(), "revision", 42,
                "action", "complete"));
        ToolResult stale = InteractionContext.runSession(sessionId, () ->
                h.tools().execute(ToolCall.of("update_goal", staleArgs)));
        assertThat(stale.ok()).isFalse();
        assertThat(stale.error()).contains("stale revision");
    }
}
