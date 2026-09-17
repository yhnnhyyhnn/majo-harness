package io.majo.harness.compaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.majo.harness.agent.loop.AgentLoopPlugin;
import io.majo.harness.agent.loop.MessageDeriver;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Compaction semantics (dsh compaction-basic): pressure-triggered and manual
 * summarization persists as a durable CONTEXT_COMPACTION event, derivation
 * restarts from the summary, and the "model-visible means logged" invariant
 * survives the rewrite.
 */
class CompactionTest {

    private static Context harness(Object compactionConfig) {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new LLMServicePlugin(), Map.of("defaultModel", "model")).await().join();
        ctx.plugin(new AgentLoopPlugin(), null).await().join();
        ctx.plugin(new CompactionPlugin(), compactionConfig).await().join();
        return ctx;
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
    void pressureTriggersAutoCompactionAndTheInvariantSurvives() {
        Context ctx = harness(Map.of("maxTokens", 150));
        SessionService sessions = ctx.get(SessionService.NAME);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        LLMService llm = ctx.get(LLMService.NAME);
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        registerEchoTool(tools);

        String sessionId = sessions.createSession();
        String longText = "user brief marker-XYZ-1234 " + "detail ".repeat(300);
        List<ChatRequest> finalRequests = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        llm.registerModel("model", new ChatModel() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                boolean summarize = request.messages().stream()
                        .anyMatch(message -> message.content() != null
                                && message.content().contains(
                                        CompactionService.SUMMARIZE_INSTRUCTION));
                if (summarize) {
                    // the compactor's own side-call: not a loop request, the
                    // loop invariant does not apply to it
                    return ChatResponse.text(
                            "The user submitted a long brief and an echo tool was requested.");
                }
                // capture the invariant AT ASK TIME: the request must equal
                // system prompt + what the durable log derives right now
                List<ChatMessage> expected = new ArrayList<>();
                expected.add(ChatMessage.system(
                        io.majo.harness.agent.loop.AgentLoopService.DEFAULT_SYSTEM_PROMPT));
                expected.addAll(MessageDeriver.derive(sessions.events(sessionId)));
                if (!request.messages().equals(expected)) {
                    violations.add("request diverged from the log");
                }
                boolean sawTool = request.messages().stream()
                        .anyMatch(message -> message.role() == io.majo.harness.llm.ChatRole.TOOL);
                if (!sawTool) {
                    return ChatResponse.toolCalls(List.of(ToolCall.of("echo", "{}")));
                }
                finalRequests.add(request);
                return ChatResponse.text("done");
            }
        });

        String answer = loop.runTurn(sessionId, longText);
        assertThat(answer).isEqualTo("done");
        assertThat(violations).isEmpty();

        // the durable log carries the compaction event
        assertThat(sessions.events(sessionId).stream()
                .anyMatch(event -> event.type() == SessionEventType.CONTEXT_COMPACTION)).isTrue();
        // the final request sees the summary, not the original wall of text
        assertThat(finalRequests).hasSize(1);
        String joined = finalRequests.get(0).messages().stream()
                .map(message -> message.content() == null ? "" : message.content())
                .reduce("", String::concat);
        assertThat(joined).contains("[conversation summary]", "long brief");
        assertThat(joined).doesNotContain("marker-XYZ-1234");
        // pressure dropped under budget after the compaction
        CompactionService compaction = ctx.get(CompactionService.NAME);
        assertThat(compaction.estimateTokens(sessionId)).isLessThanOrEqualTo(150);
    }

    @Test
    void manualCompactNowPersistsAndCollapsesDerivation() {
        Context ctx = harness(Map.of("maxTokens", 100_000)); // never auto-compact
        SessionService sessions = ctx.get(SessionService.NAME);
        LLMService llm = ctx.get(LLMService.NAME);
        llm.registerModel("model", new ChatModel() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                return ChatResponse.text("summary-of-record");
            }
        });
        String sessionId = sessions.createSession();
        sessions.append(sessionId, SessionEventType.USER_MESSAGE,
                Map.of(io.majo.harness.session.SessionEvent.FIELD_CONTENT, "hello"));

        CompactionService compaction = ctx.get(CompactionService.NAME);
        String summary = compaction.compactNow(sessionId);
        assertThat(summary).isEqualTo("summary-of-record");

        List<SessionEventType> kinds = sessions.events(sessionId).stream()
                .map(SessionEvent::type).toList();
        assertThat(kinds).containsExactly(
                SessionEventType.USER_MESSAGE, SessionEventType.CONTEXT_COMPACTION);
        // derivation collapses to the summary only
        List<ChatMessage> derived = MessageDeriver.derive(sessions.events(sessionId));
        assertThat(derived).hasSize(1);
        assertThat(derived.get(0).content()).isEqualTo("[conversation summary] summary-of-record");
    }

    @Test
    void compactNowOnAnEmptySessionIsANoOp() {
        Context ctx = harness(Map.of("maxTokens", 100_000));
        SessionService sessions = ctx.get(SessionService.NAME);
        LLMService llm = ctx.get(LLMService.NAME);
        llm.registerModel("model", new ChatModel() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                return ChatResponse.text("should not be asked");
            }
        });
        String sessionId = sessions.createSession();
        CompactionService compaction = ctx.get(CompactionService.NAME);
        assertThat(compaction.compactNow(sessionId)).isNull();
        assertThat(sessions.events(sessionId)).isEmpty();
    }

    @Test
    void tinyBudgetFailsLoud() {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new LLMServicePlugin(), Map.of("defaultModel", "model")).await().join();
        assertThatThrownBy(() -> ctx.plugin(new CompactionPlugin(), Map.of("maxTokens", 10))
                .await().join())
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTokens must be >= 100");
    }
}
