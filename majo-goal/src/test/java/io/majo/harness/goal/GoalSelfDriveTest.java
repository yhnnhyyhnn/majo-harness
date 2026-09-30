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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The idle-creation drive gap: a goal created while the session is idle
 * (e.g. via /goal) must drive its first round WITHOUT any turn-close event
 * to lean on — create itself is the trigger.
 */
class GoalSelfDriveTest {

    @Test
    void createDrivesTheFirstRoundWithoutAnyExplicitPoke() throws Exception {
        Context ctx = Context.create();
        ctx.plugin(new io.majo.harness.session.SessionPlugin(), Map.of("store", "memory"))
                .await().join();
        ctx.plugin(new io.majo.harness.session.SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new io.majo.harness.tools.ToolsPlugin(), null).await().join();
        ctx.plugin(new io.majo.harness.llm.LLMServicePlugin(), Map.of("defaultModel", "model"))
                .await().join();
        ((LLMService) ctx.get(LLMService.NAME)).registerModel("model",
                (ChatModel) request -> ChatResponse.text("round progress"));
        ctx.plugin(new io.majo.harness.agent.loop.AgentLoopPlugin(), null).await().join();
        SessionService sessions = ctx.get(SessionService.NAME);
        GoalService goals = new GoalService(ctx, null, sessions,
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME));

        String sessionId = sessions.createSession();
        goals.create(sessionId, "self-driven objective", null);
        // NO maybeDrive call — create itself must have queued the round

        long deadline = System.currentTimeMillis() + 5000;
        long count = 0;
        while (System.currentTimeMillis() < deadline) {
            count = sessions.events(sessionId).stream()
                    .filter(event -> event.type() == SessionEventType.USER_MESSAGE)
                    .filter(event -> "goal".equals(event.fields()
                            .get(SessionEvent.FIELD_PRODUCER)))
                    .count();
            if (count >= 1) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(count).as("goal-produced round message (self-drive loops; mock never completes)").isGreaterThanOrEqualTo(1);
        // and rounds actually RAN (the mock answers, turns close)
        assertThat(sessions.events(sessionId))
                .filteredOn(event -> event.type() == SessionEventType.TURN_END)
                .isNotEmpty();
    }
}
