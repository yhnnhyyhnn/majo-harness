package io.majo.harness.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.majo.harness.credentials.CredentialsPlugin;
import io.majo.harness.credentials.CredentialsService;
import io.majo.harness.credentials.CredentialProvider;
import io.majo.harness.llm.ChatModel;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.LLMService;
import io.majo.harness.llm.LLMServicePlugin;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionService;
import io.majo.harness.session.SessionProjectionsPlugin;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import io.majo.harness.tools.ToolsPlugin;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CredentialsRedactionTest {

    private static final String SECRET = "sk-live-abc123";

    @Test
    void toolResultsAndAnswersAreRedactedBeforePersisting() {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new LLMServicePlugin(), Map.of("defaultModel", "model")).await().join();
        ctx.plugin(new CredentialsPlugin(), Map.of("sourceEnv", false)).await().join();
        ctx.plugin(new AgentLoopPlugin(), null).await().join();

        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        tools.register(new Tool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.of("leak", "returns a secret-looking payload");
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.ok("value=" + SECRET,
                        Map.of("hits", List.of(Map.of("title", "contains " + SECRET))));
            }
        });

        LLMService llm = ctx.get(LLMService.NAME);
        llm.registerModel("model", new ChatModel() {
            @Override
            public ChatResponse complete(io.majo.harness.llm.ChatRequest request) {
                boolean sawTool = request.messages().stream()
                        .anyMatch(m -> m.role() == io.majo.harness.llm.ChatRole.TOOL);
                if (!sawTool) {
                    return ChatResponse.toolCalls(List.of(ToolCall.of("leak", "{}")));
                }
                return ChatResponse.text("summary mentions " + SECRET);
            }
        });

        // simulate an earlier credential resolution so the redaction set is live
        CredentialsService credentials = ctx.get(CredentialsService.NAME);
        credentials.register(new CredentialProvider() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public Optional<String> resolve(String name) {
                return "token".equals(name) ? Optional.of(SECRET) : Optional.empty();
            }
        });
        assertThat(credentials.resolve("token")).isEqualTo(SECRET);

        SessionService sessions = ctx.get(SessionService.NAME);
        String sessionId = sessions.createSession();
        AgentLoopService loop = ctx.get(AgentLoopService.NAME);
        String answer = loop.runTurn(sessionId, "probe");
        assertThat(answer).doesNotContain(SECRET);

        List<String> stored = sessions.events(sessionId).stream()
                .map(SessionEvent::content)
                .filter(content -> content != null && content.contains(SECRET))
                .toList();
        assertThat(stored).isEmpty();
        // sanitized representations survive
        assertThat(sessions.events(sessionId).stream()
                .map(event -> String.valueOf(event.fields()))
                .filter(text -> text.contains("[redacted]"))).isNotEmpty();
        assertThat(sessions.events(sessionId).stream()
                .filter(event -> event.type() == SessionEventType.TOOL_RESULT)
                .map(event -> event.fields().toString())
                .anyMatch(text -> text.contains("[redacted]"))).isTrue();
        ctx.fiber().disposeAsync().join();
    }
}
