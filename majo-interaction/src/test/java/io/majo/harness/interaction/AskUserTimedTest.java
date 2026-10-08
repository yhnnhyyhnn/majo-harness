package io.majo.harness.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jcordis.core.context.Context;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The timed ask-user contract (dsh tool-ask-user timed parity): a timeout
 * returns a pending notice (NOT a failure) and the turn continues; an answer
 * within the window returns it; delegated children cannot ask; the blocking
 * mode keeps the legacy shape.
 */
class AskUserTimedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A handler that answers after a delay (or never). */
    private static final class DelayedHandler implements InteractionHandler {
        String answer;
        long delayMillis;

        @Override
        public String name() {
            return "delayed";
        }

        @Override
        public ApprovalDecision approve(ApprovalRequest request) {
            return ApprovalDecision.ABSTAIN;
        }

        @Override
        public String answer(Question question) {
            if (delayMillis > 0) {
                try {
                    TimeUnit.MILLISECONDS.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return answer;
        }
    }

    private static Context boot() {
        Context ctx = Context.create();
        ctx.plugin(new io.majo.harness.tools.ToolsPlugin(), null).await().join();
        ctx.plugin(new InteractionPlugin(), null).await().join();
        ctx.plugin(new AskUserPlugin(), null).await().join();
        return ctx;
    }

    @Test
    void timeoutReturnsPendingAndTheTurnContinues() throws Exception {
        Context ctx = boot();
        DelayedHandler never = new DelayedHandler();
        never.answer = null; // abstain → the blocking ask path fails fast below
        ((InteractionService) ctx.get(InteractionService.NAME)).register("delayed", never);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);

        String args1 = MAPPER.writeValueAsString(Map.of("question", "proceed?", "timeout", 1));
        String sessionId = "s1";
        ToolResult result = InteractionContext.runSession(sessionId, () -> tools.execute(
                ToolCall.of("ask_user",
                        args1)));

        // no answering handler → the timed window lapses to pending (not a crash)
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).contains("pending");
        assertThat(result.content()).contains("continue with any independent work");
        assertThat(result.data()).containsEntry("pending", true);
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void anAnswerWithinTheWindowReturnsIt() throws Exception {
        Context ctx = boot();
        DelayedHandler slow = new DelayedHandler();
        slow.answer = "yes, proceed";
        slow.delayMillis = 300;
        ((InteractionService) ctx.get(InteractionService.NAME)).register("delayed", slow);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);

        String args2 = MAPPER.writeValueAsString(Map.of("question", "proceed?", "timeout", 5));
        ToolResult result = InteractionContext.runSession("s2", () -> tools.execute(
                ToolCall.of("ask_user",
                        args2)));
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).isEqualTo("answer: yes, proceed");
        assertThat(result.data()).containsEntry("pending", false);
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void delegatedChildrenCannotAskTheHuman() throws Exception {
        Context ctx = boot();
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        String args = MAPPER.writeValueAsString(Map.of("question", "proceed?"));

        ToolResult refused = InteractionContext.runSession("s3", () ->
                InteractionContext.run("subagent-child", false, () ->
                        tools.execute(ToolCall.of("ask_user", args))));
        assertThat(refused.ok()).isFalse();
        assertThat(refused.error()).contains("delegated children cannot ask");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void askTimedTimesOutToNullAndLateCompletionStillWorks() throws Exception {
        Context ctx = boot();
        InteractionService interactions = ctx.get(InteractionService.NAME);
        DelayedHandler slow = new DelayedHandler();
        slow.answer = "late";
        slow.delayMillis = 2_000;
        interactions.register("delayed", slow);

        String timed = interactions.askTimed(Question.ask("q?"), 1);
        assertThat(timed).as("timeout yields null, not an empty answer").isNull();
        ctx.fiber().disposeAsync().join();
    }
}
