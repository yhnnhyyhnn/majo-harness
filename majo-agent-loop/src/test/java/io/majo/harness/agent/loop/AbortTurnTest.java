package io.majo.harness.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.majo.harness.llm.ChatMessage;
import io.majo.harness.llm.ChatModel;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.LLMService;
import io.majo.harness.llm.LLMServicePlugin;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionProjectionsPlugin;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import io.majo.harness.tools.ToolsPlugin;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Minimal cancellation (dsh abort analog, cooperative): {@link
 * AgentLoopService#abort} closes the running turn durably with reason
 * {@code aborted} — at the next step boundary or before the next tool
 * dispatch — returning the answer produced so far. Failures keep the pinned
 * failure contract (no TURN_END); a stale abort on an idle session never
 * kills a fresh turn.
 */
class AbortTurnTest {

    private static final class Harness {
        final Context ctx = Context.create();
        final SessionService sessions;
        final LLMService llm;
        final AgentLoopService loop;

        Harness() {
            ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
            ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
            ctx.plugin(new ToolsPlugin(), null).await().join();
            ctx.plugin(new LLMServicePlugin(), Map.of("defaultModel", "model")).await().join();
            ctx.plugin(new AgentLoopPlugin(), null).await().join();
            sessions = ctx.get(SessionService.NAME);
            llm = ctx.get(LLMService.NAME);
            loop = ctx.get(AgentLoopService.NAME);
        }
    }

    private static SessionEvent lastTurnEnd(SessionService sessions, String sessionId) {
        List<SessionEvent> events = sessions.events(sessionId);
        return events.stream().filter(event -> event.type() == SessionEventType.TURN_END)
                .reduce((first, second) -> second).orElse(null);
    }

    @Test
    void abortBeforeToolDispatchClosesTheTurnAborted() throws Exception {
        Harness h = new Harness();
        CountDownLatch inFirstCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        h.llm.registerModel("model", new ChatModel() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                inFirstCall.countDown();
                try {
                    // park the turn mid-request so the abort cannot slip past
                    // the step boundary by racing ahead of it
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return ChatResponse.toolCalls(List.of(ToolCall.of("echo", "{}")));
            }
        });
        ToolRegistry tools = h.ctx.get(ToolRegistry.NAME);
        tools.register(new io.majo.harness.tools.Tool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.of("echo", "returns a fixed payload");
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.ok("echo-result", Map.of());
            }
        });

        String sessionId = h.sessions.createSession();
        // abort while the first (tool-round) request is parked mid-flight
        Thread turn = new Thread(() -> h.loop.runTurn(sessionId, "long-running"));
        turn.start();
        assertThat(inFirstCall.await(5, TimeUnit.SECONDS)).isTrue();
        h.loop.abort(sessionId);
        release.countDown();
        turn.join(5_000);

        SessionEvent end = lastTurnEnd(h.sessions, sessionId);
        assertThat(end).isNotNull();
        assertThat(end.fields().get(SessionEvent.FIELD_REASON)).isEqualTo("aborted");
        // the pending tool result was never dispatched: the log ends at the turn
        List<SessionEventType> types = h.sessions.events(sessionId).stream()
                .map(SessionEvent::type).toList();
        assertThat(types).endsWith(SessionEventType.TURN_END);
        assertThat(types).doesNotContain(SessionEventType.TOOL_RESULT);
        // a fresh turn after the aborted one completes normally
        h.llm.registerModel("model-2", (ChatModel) request -> ChatResponse.text("after abort"));
        String answer = h.loop.runTurn(sessionId, "again", null, "model-2");
        assertThat(answer).isEqualTo("after abort");
        assertThat(lastTurnEnd(h.sessions, sessionId).fields()
                .get(SessionEvent.FIELD_REASON)).isEqualTo("completed");
    }

    @Test
    void staleAbortOnIdleSessionNeverKillsAFreshTurn() {
        Harness h = new Harness();
        h.llm.registerModel("model", (ChatModel) request -> ChatResponse.text("fine"));
        String sessionId = h.sessions.createSession();

        h.loop.abort(sessionId); // nobody is running
        String answer = h.loop.runTurn(sessionId, "hello");

        assertThat(answer).isEqualTo("fine");
        assertThat(lastTurnEnd(h.sessions, sessionId).fields()
                .get(SessionEvent.FIELD_REASON)).isEqualTo("completed");
    }

    @Test
    void naturalCompletionIsMarkedCompleted() {
        Harness h = new Harness();
        h.llm.registerModel("model", (ChatModel) request -> ChatResponse.text("fine"));
        String sessionId = h.sessions.createSession();

        h.loop.runTurn(sessionId, "hello");

        assertThat(lastTurnEnd(h.sessions, sessionId).fields()
                .get(SessionEvent.FIELD_REASON)).isEqualTo("completed");
    }
}
