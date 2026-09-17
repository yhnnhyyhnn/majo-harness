package io.majo.harness.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.majo.harness.llm.ChatModel;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.LLMService;
import io.majo.harness.llm.LLMServicePlugin;
import io.majo.harness.llm.ModelException;
import io.majo.harness.llm.fault.FaultChatModel;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionProjectionsPlugin;
import io.majo.harness.session.SessionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The turn-level failure contract, pinned with {@link FaultChatModel} (the
 * seam-level half of the fault-injection infra): a model failure — including
 * a stream dying after deltas were already emitted — fails the turn loudly
 * and never leaves a half-committed assistant round or TURN_END in the
 * session log; the log stays append-consistent and the session recovers on
 * the next turn. A driver-driven turn that fails is logged and cannot wedge
 * the inbox queue.
 */
class FaultTurnTest {

    private static final class Harness {
        final Context ctx = Context.create();
        final SessionService sessions;
        final LLMService llm;
        final AgentLoopService loop;

        Harness() {
            ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
            ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
            ctx.plugin(new io.majo.harness.tools.ToolsPlugin(), null).await().join();
            ctx.plugin(new LLMServicePlugin(), Map.of("defaultModel", "model")).await().join();
            ctx.plugin(new AgentLoopPlugin(), null).await().join();
            sessions = ctx.get(SessionService.NAME);
            llm = ctx.get(LLMService.NAME);
            loop = ctx.get(AgentLoopService.NAME);
        }
    }

    private static List<SessionEventType> types(SessionService sessions, String sessionId) {
        return sessions.events(sessionId).stream().map(SessionEvent::type).toList();
    }

    @Test
    void modelFailureFailsTheTurnLoudlyAndLeavesTheLogConsistent() {
        Harness h = new Harness();
        h.llm.registerModel("model",
                new FaultChatModel(request -> ChatResponse.text("fine")).failOnCall(1, "provider exploded"));
        String sessionId = h.sessions.createSession();

        assertThatThrownBy(() -> h.loop.runTurn(sessionId, "hi"))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("provider exploded");

        // the open turn is durable bookkeeping; the assistant round is not
        // half-committed and there is no TURN_END
        assertThat(types(h.sessions, sessionId))
                .containsExactly(SessionEventType.TURN_START, SessionEventType.USER_MESSAGE,
                        SessionEventType.REQUEST_HEADER);
        assertThat(h.sessions.events(sessionId))
                .extracting(SessionEvent::seq)
                .containsExactly(1L, 2L, 3L);

        // the session recovers: the next turn lands cleanly after the open one
        String answer = h.loop.runTurn(sessionId, "again");
        assertThat(answer).isEqualTo("fine");
        assertThat(types(h.sessions, sessionId)).endsWith(SessionEventType.TURN_END);
        assertThat(h.sessions.events(sessionId))
                .extracting(SessionEvent::seq)
                .isSorted();
    }

    @Test
    void midStreamDeathIsLoudAndLeavesNoAssistantRound() {
        Harness h = new Harness();
        h.llm.registerModel("model",
                new FaultChatModel(request -> ChatResponse.text("fine"))
                        .failStreamOnCall(1, 2, "connection reset by peer"));
        String sessionId = h.sessions.createSession();
        List<String> deltas = new ArrayList<>();

        assertThatThrownBy(() -> h.loop.runTurn(sessionId, "hi", deltas::add))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("connection reset");

        assertThat(deltas).containsExactly("chunk-1 ", "chunk-2 ");
        assertThat(types(h.sessions, sessionId))
                .containsExactly(SessionEventType.TURN_START, SessionEventType.USER_MESSAGE,
                        SessionEventType.REQUEST_HEADER);
    }

    @Test
    void driverQueueSurvivesAFailedFollowup() throws Exception {
        Harness h = new Harness();
        // call 1 = the caller's turn (ok), call 2 = the driven followup (dies)
        h.llm.registerModel("model",
                new FaultChatModel(request -> ChatResponse.text("fine")).failOnCall(2, "driven turn dies"));
        String sessionId = h.sessions.createSession();
        h.loop.runTurn(sessionId, "first");
        long firstTurnEnd = h.sessions.events(sessionId).stream()
                .filter(event -> event.type() == SessionEventType.TURN_END)
                .findFirst()
                .orElseThrow()
                .seq();
        assertThat(types(h.sessions, sessionId)).endsWith(SessionEventType.TURN_END);

        h.loop.followup(sessionId, "second");
        // the driver turn fails and is logged; the queue must not wedge — a
        // followup offered afterwards still runs to completion
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (h.loop.queuedCount(sessionId) > 0 && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        h.loop.followup(sessionId, "third");
        deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        boolean recovered = false;
        while (System.currentTimeMillis() < deadline) {
            recovered = h.sessions.events(sessionId).stream()
                    .anyMatch(event -> event.type() == SessionEventType.TURN_END
                            && event.seq() > firstTurnEnd);
            if (recovered) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(recovered).as("a followup after the failed driven turn completed").isTrue();
        assertThat(types(h.sessions, sessionId)).endsWith(SessionEventType.TURN_END);
        assertThat(h.sessions.events(sessionId))
                .extracting(SessionEvent::seq)
                .isSorted();
    }
}
