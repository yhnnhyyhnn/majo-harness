package io.majo.harness.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Dual-inbox semantics: steering splices into the running turn at a step
 * boundary, followups chain after the running turn (or are auto-driven when
 * idle), and injected context never wakes the loop.
 */
class AgentInboxTest {

    private static final class Harness {
        final Context ctx = Context.create();
        final SessionService sessions;
        final ToolRegistry tools;
        final LLMService llm;
        final AgentLoopService loop;

        Harness() {
            ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
            ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
            ctx.plugin(new ToolsPlugin(), null).await().join();
            ctx.plugin(new LLMServicePlugin(), Map.of("defaultModel", "model")).await().join();
            ctx.plugin(new AgentLoopPlugin(), null).await().join();
            sessions = ctx.get(SessionService.NAME);
            tools = ctx.get(ToolRegistry.NAME);
            llm = ctx.get(LLMService.NAME);
            loop = ctx.get(AgentLoopService.NAME);
        }
    }

    private static void registerEchoTool(ToolRegistry tools) {
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
    }

    @Test
    void steerLandsAtNextStepBoundaryInsideTheRunningTurn() throws Exception {
        Harness h = new Harness();
        registerEchoTool(h.tools);
        String sessionId = h.sessions.createSession();
        CountDownLatch turnStarted = new CountDownLatch(1);
        CountDownLatch steerQueued = new CountDownLatch(1);
        AtomicInteger step = new AtomicInteger();
        AtomicReference<ChatRequest> secondRequest = new AtomicReference<>();
        h.llm.registerModel("model", new ChatModel() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                if (step.incrementAndGet() == 1) {
                    turnStarted.countDown();
                    // hold step 1 open so the steer lands before step 2
                    try {
                        steerQueued.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ChatResponse.toolCalls(List.of(ToolCall.of("echo", "{}")));
                }
                secondRequest.set(request);
                return ChatResponse.text("done");
            }
        });

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> running = caller.submit(
                    () -> h.loop.runTurn(sessionId, "original task"));
            assertThat(turnStarted.await(5, TimeUnit.SECONDS)).isTrue();
            h.loop.steer(sessionId, "steered mid-turn");
            steerQueued.countDown();
            assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo("done");

            // the steered text is model-visible in the second request, as a
            // user message after the first assistant round
            assertThat(secondRequest.get().messages().stream()
                    .filter(m -> m.role() == io.majo.harness.llm.ChatRole.USER)
                    .map(m -> m.content()).toList())
                    .containsExactly("original task", "steered mid-turn");

            // durable order inside ONE turn: user, header, assistant(toolcall),
            // tool result, steered user message, header, assistant(final)
            List<SessionEventType> kinds = h.sessions.events(sessionId).stream()
                    .map(SessionEvent::type).toList();
            assertThat(kinds).startsWith(
                    SessionEventType.TURN_START,
                    SessionEventType.USER_MESSAGE,
                    SessionEventType.REQUEST_HEADER,
                    SessionEventType.ASSISTANT_MESSAGE,
                    SessionEventType.TOOL_RESULT,
                    SessionEventType.USER_MESSAGE,
                    SessionEventType.REQUEST_HEADER,
                    SessionEventType.ASSISTANT_MESSAGE,
                    SessionEventType.TURN_END);
            assertThat(kinds).hasSize(9); // one turn only — steer did not chain a turn
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void followupQueuedDuringATurnChainsAsANewTurn() {
        Harness h = new Harness();
        String sessionId = h.sessions.createSession();
        AtomicInteger turn = new AtomicInteger();
        h.llm.registerModel("model", new ChatModel() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                if (turn.incrementAndGet() == 1) {
                    // simulate a tool/job finishing mid-turn and leaving a notice
                    h.loop.followup(sessionId, "second task");
                    return ChatResponse.text("first done");
                }
                return ChatResponse.text("second done");
            }
        });

        String answer = h.loop.runTurn(sessionId, "first task");
        assertThat(answer).isEqualTo("first done"); // the caller's own turn

        List<SessionEvent> events = h.sessions.events(sessionId);
        assertThat(events.stream().filter(e -> e.type() == SessionEventType.TURN_START).count())
                .isEqualTo(2);
        assertThat(events.stream()
                .filter(e -> e.type() == SessionEventType.USER_MESSAGE)
                .map(SessionEvent::content).toList())
                .containsExactly("first task", "second task");
        // both turns close
        assertThat(events.get(events.size() - 1).type()).isEqualTo(SessionEventType.TURN_END);
    }

    @Test
    void injectNeverWakesTheLoopButLandsAtTheNextTurnOpening() throws Exception {
        Harness h = new Harness();
        String sessionId = h.sessions.createSession();
        AtomicReference<ChatRequest> seen = new AtomicReference<>();
        h.llm.registerModel("model", new ChatModel() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                seen.set(request);
                return ChatResponse.text("ok");
            }
        });

        h.loop.inject(sessionId, "background context");
        Thread.sleep(150); // no driver may run: the log must stay untouched
        assertThat(h.sessions.events(sessionId)).isEmpty();

        h.loop.runTurn(sessionId, "hello");
        List<SessionEventType> kinds = h.sessions.events(sessionId).stream()
                .map(SessionEvent::type).toList();
        assertThat(kinds).startsWith(
                SessionEventType.TURN_START,
                SessionEventType.CONTEXT_NOTE,
                SessionEventType.USER_MESSAGE);
        assertThat(seen.get().messages().stream()
                .filter(m -> m.role() == io.majo.harness.llm.ChatRole.USER)
                .map(m -> m.content()).toList())
                .containsExactly("background context", "hello");
    }

    @Test
    void idleFollowupIsAutoDrivenByTheWakeLatch() throws Exception {
        Harness h = new Harness();
        String sessionId = h.sessions.createSession();
        h.llm.registerModel("model", new ChatModel() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                return ChatResponse.text("driven answer");
            }
        });

        h.loop.followup(sessionId, "driven task");
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<SessionEvent> events = h.sessions.events(sessionId);
            boolean complete = !events.isEmpty()
                    && events.get(events.size() - 1).type() == SessionEventType.TURN_END;
            if (complete) {
                assertThat(events.stream()
                        .filter(e -> e.type() == SessionEventType.USER_MESSAGE)
                        .map(SessionEvent::content).toList())
                        .containsExactly("driven task");
                assertThat(h.loop.queuedCount(sessionId)).isZero();
                return;
            }
            Thread.sleep(25);
        }
        org.assertj.core.api.Assertions.fail("followup turn was not driven within 5s");
    }
}
