package io.majo.harness.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.majo.harness.llm.ChatMessage;
import io.majo.harness.llm.ChatModel;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.LLMService;
import io.majo.harness.llm.LLMServicePlugin;
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
 * The core session invariant (dsh "model-visible means logged"): at the
 * moment the model is asked, its request must be exactly rebuildable from
 * the durable session log — the derived history plus the system prompt. Any
 * code path that lets model-visible content bypass the log fails here.
 */
class ModelVisibleMeansLoggedTest {

    @Test
    void everyModelRequestRebuildsFromTheLogAtAskTime() {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new LLMServicePlugin(), Map.of("defaultModel", "model")).await().join();
        ctx.plugin(new AgentLoopPlugin(), null).await().join();
        SessionService sessions = ctx.get(SessionService.NAME);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        LLMService llm = ctx.get(LLMService.NAME);
        AgentLoopService loop = ctx.get(AgentLoopService.NAME);

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

        String sessionId = sessions.createSession();
        List<String> violations = new ArrayList<>();
        int[] asks = {0};
        llm.registerModel("model", new ChatModel() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                // the REQUEST_HEADER for THIS ask is already durable: the log
                // now holds everything the request was built from
                List<ChatMessage> expected = new ArrayList<>();
                expected.add(ChatMessage.system(AgentLoopService.DEFAULT_SYSTEM_PROMPT));
                expected.addAll(MessageDeriver.derive(sessions.events(sessionId)));
                if (!request.messages().equals(expected)) {
                    violations.add("ask " + asks[0] + " diverges from the durable log");
                }
                asks[0]++;
                if (asks[0] == 1) {
                    return ChatResponse.toolCalls(List.of(ToolCall.of("echo", "{}")));
                }
                return ChatResponse.text("final answer");
            }
        });

        // injected context is model-visible but only ever rides the log
        loop.inject(sessionId, "injected context");
        String answer = loop.runTurn(sessionId, "please echo");

        assertThat(violations).isEmpty();
        assertThat(answer).isEqualTo("final answer");
        assertThat(asks[0]).isEqualTo(2); // one tool round + one final round
    }
}
