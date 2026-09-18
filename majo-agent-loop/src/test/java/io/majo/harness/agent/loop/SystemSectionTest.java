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
import io.majo.harness.tools.ToolsPlugin;
import io.majo.harness.session.SessionService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * System-prompt sections (dsh server-context analog): registered sections
 * assemble after the configured prompt in id order, and every
 * REQUEST_HEADER records the assembled prompt verbatim — the invariant
 * holds because the header IS what the model saw.
 */
class SystemSectionTest {

    @Test
    void sectionsAssembleInIdOrderAndReachTheHeader() {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new io.majo.harness.session.SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new LLMServicePlugin(), Map.of("defaultModel", "model")).await().join();
        ctx.plugin(new AgentLoopPlugin(), null).await().join();
        SessionService sessions = ctx.get(SessionService.NAME);
        LLMService llm = ctx.get(LLMService.NAME);
        AgentLoopService loop = ctx.get(AgentLoopService.NAME);

        AtomicReference<String> seenSystem = new AtomicReference<>();
        AtomicReference<List<io.majo.harness.llm.ChatMessage>> seenMessages =
                new AtomicReference<>();
        llm.registerModel("model", request -> {
            seenMessages.set(request.messages());
            request.messages().stream()
                    .filter(message -> message.role() == io.majo.harness.llm.ChatRole.SYSTEM)
                    .findFirst()
                    .ifPresent(message -> seenSystem.set(message.content()));
            return ChatResponse.text("ok");
        });

        loop.registerSystemSection("zeta", () -> "from zeta");
        loop.registerSystemSection("alpha", () -> "from alpha");
        // a blank section is skipped; a disposed one is gone
        var blank = loop.registerSystemSection("blank", () -> "  ");
        var gone = loop.registerSystemSection("gone", () -> "should not appear");
        gone.dispose();

        String sessionId = sessions.createSession();
        sessions.append(sessionId, SessionEventType.USER_MESSAGE,
                Map.of(SessionEvent.FIELD_CONTENT, "hello"));
        String answer = loop.runTurn(sessionId, "hello");
        assertThat(answer).isEqualTo("ok");

        assertThat(seenMessages.get()).as("model was called").isNotNull();
        assertThat(seenSystem.get())
                .as("system prompt: %s", seenMessages.get().get(0).content())
                .startsWith("You are a helpful agent harness.")
                .contains("# alpha\nfrom alpha", "# zeta\nfrom zeta")
                .doesNotContain("gone");
        // REQUEST_HEADER recorded the assembled prompt verbatim
        assertThat(String.valueOf(sessions.events(sessionId).stream()
                .filter(event -> event.type() == SessionEventType.REQUEST_HEADER)
                .findFirst().orElseThrow()
                .fields().get(SessionEvent.FIELD_SYSTEM_PROMPT)))
                .contains("from alpha");
    }
}
