package io.majo.harness.plan;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.interaction.InteractionPlugin;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionProjections;
import io.majo.harness.session.SessionProjectionsPlugin;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolsPlugin;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * exit_plan_mode semantics (dsh plan-mode): review through the interaction
 * seam, approval records the deactivation, feedback keeps the plan active
 * and rides the tool result back to the model, and a dismissed review
 * (empty answer) neither approves nor feeds back — it ends the turn
 * cleanly without another model request (dsh concludeTurn parity).
 */
class PlanTest {

    private static Context harness(String cannedAnswer) {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new io.majo.harness.llm.LLMServicePlugin(), Map.of("defaultModel", "model"))
                .await().join();
        ctx.plugin(new io.majo.harness.agent.loop.AgentLoopPlugin(), null).await().join();
        ctx.plugin(new InteractionPlugin(), cannedAnswer == null
                ? Map.of()
                : Map.of("answer", cannedAnswer)).await().join();
        ctx.plugin(new PlanPlugin(), null).await().join();
        return ctx;
    }

    private static ToolResult callExit(Context ctx, String sessionId) {
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        return InteractionContext.runSession(sessionId,
                () -> tools.execute(ToolCall.of("exit_plan_mode", "{\"summary\":\"storage refactor\"}")));
    }

    @Test
    void callingWithoutAnActivePlanFailsLoudly() {
        Context ctx = harness(null);
        SessionService sessions = ctx.get(SessionService.NAME);
        String sessionId = sessions.createSession();

        ToolResult result = callExit(ctx, sessionId);

        assertThat(result.ok()).isFalse();
        assertThat(result.visibleText()).contains("no plan is active");
        assertThat(sessions.events(sessionId)).isEmpty();
    }

    @Test
    void approvalRecordsDeactivationAndGreenLightsImplementation() {
        Context ctx = harness("canned:approve");
        SessionService sessions = ctx.get(SessionService.NAME);
        SessionProjections projections = ctx.get(SessionProjections.NAME);
        String sessionId = sessions.createSession();
        sessions.append(sessionId, SessionEventType.PLAN_SET,
                Map.of(io.majo.harness.session.SessionEvent.FIELD_ACTIVE, true,
                        io.majo.harness.session.SessionEvent.FIELD_PLAN, "1. measure\n2. refactor"));
        PlanState plans = projections.require(PlanState.KEY);
        assertThat(plans.snapshot(sessionId).active()).isTrue();

        ToolResult result = callExit(ctx, sessionId);

        assertThat(result.ok()).isTrue();
        assertThat(result.visibleText()).contains("proceed with implementation");
        assertThat(plans.snapshot(sessionId).active()).isFalse();
        // the durable deactivation rides the log
        SessionEvent last = sessions.events(sessionId).get(1);
        assertThat(last.type()).isEqualTo(SessionEventType.PLAN_SET);
        assertThat(last.fields().get(io.majo.harness.session.SessionEvent.FIELD_ACTIVE)).isEqualTo(false);
    }

    @Test
    void feedbackKeepsThePlanActiveAndRidesTheToolResult() {
        Context ctx = harness("canned:also migrate the reports module");
        SessionService sessions = ctx.get(SessionService.NAME);
        SessionProjections projections = ctx.get(SessionProjections.NAME);
        String sessionId = sessions.createSession();
        sessions.append(sessionId, SessionEventType.PLAN_SET,
                Map.of(io.majo.harness.session.SessionEvent.FIELD_ACTIVE, true,
                        io.majo.harness.session.SessionEvent.FIELD_PLAN, "1. measure"));
        PlanState plans = projections.require(PlanState.KEY);

        ToolResult result = callExit(ctx, sessionId);

        assertThat(result.ok()).isTrue();
        assertThat(result.visibleText())
                .contains("also migrate the reports module")
                .contains("revise the plan");
        assertThat(plans.snapshot(sessionId).active()).isTrue();
        // no durable deactivation: the log still holds only the activation
        assertThat(sessions.events(sessionId)).hasSize(1);
    }

    @Test
    void dismissedReviewNeitherApprovesNorFeedsBack() {
        Context ctx = harness("canned:");
        SessionService sessions = ctx.get(SessionService.NAME);
        SessionProjections projections = ctx.get(SessionProjections.NAME);
        String sessionId = sessions.createSession();
        sessions.append(sessionId, SessionEventType.PLAN_SET,
                Map.of(io.majo.harness.session.SessionEvent.FIELD_ACTIVE, true,
                        io.majo.harness.session.SessionEvent.FIELD_PLAN, "1. measure"));
        PlanState plans = projections.require(PlanState.KEY);

        ToolResult result = callExit(ctx, sessionId);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("approved")).isEqualTo(false);
        assertThat(result.visibleText())
                .contains("dismissed the plan review")
                .contains("remains active");
        // no approval, no feedback: the plan stays active and nothing durable
        // was appended beyond the activation
        assertThat(plans.snapshot(sessionId).active()).isTrue();
        assertThat(sessions.events(sessionId)).hasSize(1);
    }

    @Test
    void dismissedReviewEndsTheTurnWithoutAnotherModelRequest() {
        Context ctx = harness("canned:");
        SessionService sessions = ctx.get(SessionService.NAME);
        SessionProjections projections = ctx.get(SessionProjections.NAME);
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        ((io.majo.harness.llm.LLMService) ctx.get(io.majo.harness.llm.LLMService.NAME))
                .registerModel("model", request -> {
                    // the second request happens only if the dismissal
                    // regresses to "ask the model to wait"
                    return requests.incrementAndGet() == 1
                            ? io.majo.harness.llm.ChatResponse.toolCalls(java.util.List.of(
                                    ToolCall.of("exit_plan_mode", "{\"summary\":\"refactor\"}")))
                            : io.majo.harness.llm.ChatResponse.text("waiting for your message.");
                });
        String sessionId = sessions.createSession();
        sessions.append(sessionId, SessionEventType.PLAN_SET,
                Map.of(io.majo.harness.session.SessionEvent.FIELD_ACTIVE, true,
                        io.majo.harness.session.SessionEvent.FIELD_PLAN, "1. measure"));
        PlanState plans = projections.require(PlanState.KEY);

        loop.runTurn(sessionId, "plan the storage refactor");

        assertThat(requests.get()).isEqualTo(1);
        assertThat(plans.snapshot(sessionId).active()).isTrue();
        // the turn closed completed (not aborted) with the tool result durable
        SessionEvent turnEnd = sessions.events(sessionId).stream()
                .filter(event -> event.type() == SessionEventType.TURN_END)
                .findFirst().orElseThrow();
        assertThat(turnEnd.fields()
                .get(io.majo.harness.session.SessionEvent.FIELD_REASON)).isEqualTo("completed");
        assertThat(sessions.events(sessionId).stream()
                .anyMatch(event -> event.type() == SessionEventType.TOOL_RESULT)).isTrue();
    }
}
