package io.majo.harness.web;

import io.jcordis.core.context.Context;
import io.majo.harness.interaction.Question;
import io.majo.harness.llm.ChatModel;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.LLMService;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Late-answer delivery for timed asks (dsh answer_to_pending_question
 * parity): a timed question that lapsed to pending stays open — the human's
 * later answer lands in the session as a marked user follow-up.
 */
class TimedAskDeliveryTest {

    @Test
    void lateAnswerDeliversAsAMarkedUserMessage() throws Exception {
        Context ctx = Context.create();
        ctx.plugin(new io.majo.harness.session.SessionPlugin(), Map.of("store", "memory"))
                .await().join();
        ctx.plugin(new io.majo.harness.session.SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new io.majo.harness.tools.ToolsPlugin(), null).await().join();
        ctx.plugin(new io.majo.harness.llm.LLMServicePlugin(), Map.of("defaultModel", "model"))
                .await().join();
        ((LLMService) ctx.get(LLMService.NAME)).registerModel("model",
                (ChatModel) request -> ChatResponse.text("ok"));
        ctx.plugin(new io.majo.harness.agent.loop.AgentLoopPlugin(), null).await().join();
        SessionService sessions = ctx.get(SessionService.NAME);
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);

        PendingInteractions pending = new PendingInteractions(30);
        pending.bindLoop(loop);
        String sessionId = sessions.createSession();

        // the timed ask lapses (nobody answers within the window)
        Question question = Question.ask("which database?");
        String lapsed = pending.askTimed(question, sessionId, 1);
        assertThat(lapsed).isNull();
        // the registration survives for the late answer
        assertThat(pending.openTimedQuestions()).hasSize(1);

        // the human answers later through the same questions endpoint
        assertThat(pending.answerQuestion(question.id(), "postgres")).isTrue();
        assertThat(pending.openTimedQuestions()).isEmpty();

        // the marked answer lands in the session as a user follow-up
        long deadline = System.currentTimeMillis() + 5000;
        boolean delivered = false;
        while (System.currentTimeMillis() < deadline && !delivered) {
            delivered = sessions.events(sessionId).stream()
                    .anyMatch(event -> event.type() == SessionEventType.USER_MESSAGE
                            && event.content() != null
                            && event.content().startsWith("[answer_to_pending_question]")
                            && event.content().contains("postgres"));
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(delivered).as("late answer delivered as a marked user message").isTrue();
        // answering again is rejected (already resolved)
        assertThat(pending.answerQuestion(question.id(), "mysql")).isFalse();
        ctx.fiber().disposeAsync().join();
    }
}
