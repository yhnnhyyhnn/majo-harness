package io.majo.harness.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.majo.harness.llm.ChatMessage;
import io.majo.harness.llm.ChatModel;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.LLMService;
import io.majo.harness.llm.LLMServicePlugin;
import io.majo.harness.llm.TokenUsage;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionProjectionsPlugin;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.ToolsPlugin;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Token metering (dsh TokenUsage parity) is durable: when the provider
 * reports usage, the assistant round logs it — input/output plus the cache
 * components when present — so per-request consumption is observable from
 * the session log alone.
 */
class UsageLoggingTest {

    @Test
    void providerUsageLandsOnTheAssistantRound() {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new LLMServicePlugin(), Map.of("defaultModel", "model")).await().join();
        ctx.plugin(new AgentLoopPlugin(), null).await().join();
        SessionService sessions = ctx.get(SessionService.NAME);
        LLMService llm = ctx.get(LLMService.NAME);
        AgentLoopService loop = ctx.get(AgentLoopService.NAME);

        llm.registerModel("model", new ChatModel() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                return ChatResponse.text("final answer",
                        TokenUsage.of(120, 30, 100L, 20L));
            }
        });

        String sessionId = sessions.createSession();
        loop.runTurn(sessionId, "hello");

        SessionEvent assistant = sessions.events(sessionId).stream()
                .filter(event -> event.type() == SessionEventType.ASSISTANT_MESSAGE)
                .findFirst().orElseThrow();
        assertThat(assistant.fields().get(SessionEvent.FIELD_INPUT_TOKENS)).isEqualTo(120L);
        assertThat(assistant.fields().get(SessionEvent.FIELD_OUTPUT_TOKENS)).isEqualTo(30L);
        assertThat(assistant.fields().get(SessionEvent.FIELD_CACHE_READ_TOKENS)).isEqualTo(100L);
        assertThat(assistant.fields().get(SessionEvent.FIELD_CACHE_WRITE_TOKENS)).isEqualTo(20L);
    }

    @Test
    void absentUsageLeavesNoFields() {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new LLMServicePlugin(), Map.of("defaultModel", "model")).await().join();
        ctx.plugin(new AgentLoopPlugin(), null).await().join();
        SessionService sessions = ctx.get(SessionService.NAME);
        LLMService llm = ctx.get(LLMService.NAME);
        AgentLoopService loop = ctx.get(AgentLoopService.NAME);

        llm.registerModel("model", (ChatModel) request -> ChatResponse.text("plain"));

        String sessionId = sessions.createSession();
        loop.runTurn(sessionId, "hello");

        SessionEvent assistant = sessions.events(sessionId).stream()
                .filter(event -> event.type() == SessionEventType.ASSISTANT_MESSAGE)
                .findFirst().orElseThrow();
        assertThat(assistant.fields()).doesNotContainKeys(
                SessionEvent.FIELD_INPUT_TOKENS, SessionEvent.FIELD_OUTPUT_TOKENS);
    }
}
