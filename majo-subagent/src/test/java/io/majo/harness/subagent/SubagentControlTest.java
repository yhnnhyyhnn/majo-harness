package io.majo.harness.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jcordis.core.context.Context;
import io.majo.harness.llm.ChatMessage;
import io.majo.harness.llm.ChatModel;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatRole;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.LLMService;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The child-agent control family (dsh tool-subagent-control parity):
 * send_message continues a live child with its prior history through the
 * remembered delegation shape, list_agents surfaces the children, and
 * interrupt aborts a child's running turn cooperatively.
 */
class SubagentControlTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Harness(Context ctx, SessionService sessions, SubagentService subagent,
            ToolRegistry tools) {

        static Harness mount() {
            Context ctx = Context.create();
            ctx.plugin(new io.majo.harness.session.SessionPlugin(), Map.of("store", "memory"))
                    .await().join();
            ctx.plugin(new io.majo.harness.session.SessionProjectionsPlugin(), null).await().join();
            ctx.plugin(new io.majo.harness.tools.ToolsPlugin(), null).await().join();
            ctx.plugin(new io.majo.harness.llm.LLMServicePlugin(), Map.of("defaultModel", "model"))
                    .await().join();
            ctx.plugin(new io.majo.harness.agent.loop.AgentLoopPlugin(), null).await().join();
            ctx.plugin(new SubagentPlugin(), Map.of("maxDepth", 3)).await().join();
            ctx.plugin(new SubagentToolPlugin(), null).await().join();
            return new Harness(ctx, ctx.get(SessionService.NAME),
                    ctx.get(SubagentService.NAME), ctx.get(ToolRegistry.NAME));
        }
    }

    /** A model that answers with the count of user-visible messages it saw. */
    private static void registerCountingModel(LLMService llm, AtomicReference<Integer> lastSeen) {
        llm.registerModel("model", (ChatModel) request -> {
            long users = request.messages().stream()
                    .filter(message -> message.role() == ChatRole.USER
                            || message.role() == ChatRole.TOOL)
                    .count();
            lastSeen.set((int) users);
            return ChatResponse.text("saw-" + users);
        });
    }

    @Test
    void sendMessageContinuesTheChildWithItsPriorHistory() {
        Harness h = Harness.mount();
        LLMService llm = h.ctx().get(LLMService.NAME);
        AtomicReference<Integer> lastSeen = new AtomicReference<>(0);
        registerCountingModel(llm, lastSeen);

        SubagentService.DelegationOutcome first = h.subagent().delegateWithChild("first task");
        assertThat(first.answer()).isEqualTo("saw-1");

        SubagentService.DelegationOutcome second =
                h.subagent().sendMessage(first.childSessionId(), "follow-up");
        // the child session persisted: the continuation sees both messages
        assertThat(second.answer()).isEqualTo("saw-2");
        assertThat(second.childSessionId()).isEqualTo(first.childSessionId());
    }

    @Test
    void sendMessageToUnknownChildFailsLoud() {
        Harness h = Harness.mount();
        assertThatThrownBy(() -> h.subagent().sendMessage("nope", "hi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown child session");
    }

    @Test
    void listAgentsSurfacesChildrenAndTheToolRendersThem() throws Exception {
        Harness h = Harness.mount();
        LLMService llm = h.ctx().get(LLMService.NAME);
        AtomicReference<Integer> seen = new AtomicReference<>(0);
        registerCountingModel(llm, seen);

        SubagentService.DelegationOutcome child = h.subagent().delegateWithChild("write the report");
        assertThat(h.subagent().agents()).hasSize(1);
        SubagentService.AgentEntry entry = h.subagent().agents().get(0);
        assertThat(entry.childSessionId()).isEqualTo(child.childSessionId());
        assertThat(entry.status()).isEqualTo("done");
        assertThat(entry.task()).isEqualTo("write the report");
        assertThat(entry.lastAnswerPreview()).contains("saw-1");

        ToolResult listing = h.tools().execute(ToolCall.of("list_agents", "{}"));
        assertThat(listing.ok()).isTrue();
        assertThat(listing.content()).contains(child.childSessionId());
        assertThat(listing.content()).contains("write the report");

        // through the tool: send_message by session id
        ToolResult sent = h.tools().execute(ToolCall.of("send_message",
                MAPPER.writeValueAsString(Map.of(
                        "session_id", child.childSessionId(), "message", "one more"))));
        assertThat(sent.ok()).isTrue();
        assertThat(sent.content()).isEqualTo("saw-2");
        assertThat(sent.data()).containsEntry("childSessionId", child.childSessionId());
    }

    @Test
    void interruptRequestsCooperativeCancellation() throws Exception {
        Harness h = Harness.mount();
        LLMService llm = h.ctx().get(LLMService.NAME);
        registerCountingModel(llm, new AtomicReference<>(0));
        SubagentService.DelegationOutcome child = h.subagent().delegateWithChild("task");

        // idle child: the abort flag is set (stale-abort-safe at the loop)
        assertThat(h.subagent().interrupt(child.childSessionId())).isTrue();
        assertThat(h.subagent().agent(child.childSessionId()).status())
                .isEqualTo("interrupt-requested");
        // the child stays continuable after an interrupt
        assertThat(h.subagent().sendMessage(child.childSessionId(), "again").answer())
                .isEqualTo("saw-2");
        // unknown ids are false, and the tool surfaces the error
        assertThat(h.subagent().interrupt("ghost")).isFalse();
        String ghostArgs = MAPPER.writeValueAsString(Map.of("session_id", "ghost"));
        ToolResult result = h.tools().execute(ToolCall.of("interrupt_agent", ghostArgs));
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("unknown child session");
    }

    @Test
    void scopedChildrenContinueInsideTheirAgentScope() {
        Harness h = Harness.mount();
        LLMService llm = h.ctx().get(LLMService.NAME);
        llm.registerModel("model", (ChatModel) request -> ChatResponse.text("root-ok"));
        llm.registerModel("child-model", (ChatModel) request -> ChatResponse.text("scoped-ok"));

        SubagentService.DelegationOutcome child = h.subagent().delegateSpec("scoped task",
                new SubagentService.AgentSpec("child-model", null, null));
        assertThat(child.answer()).isEqualTo("scoped-ok");

        // the continuation re-mounts the remembered spec (child-model again)
        SubagentService.DelegationOutcome continued =
                h.subagent().sendMessage(child.childSessionId(), "continue");
        assertThat(continued.answer()).isEqualTo("scoped-ok");
    }


}
