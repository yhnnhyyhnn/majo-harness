package io.majo.harness.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.majo.harness.agent.loop.AgentLoopPlugin;
import io.majo.harness.llm.ChatModel;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.ChatMessage;
import io.majo.harness.llm.LLMService;
import io.majo.harness.llm.LLMServicePlugin;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionProjectionsPlugin;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolsPlugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubagentSeamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ChatModel FINAL_MODEL = request ->
            ChatResponse.text("child-result");

    /** Mounts the full loop stack plus the subagent seam on a fresh context. */
    private static Context stack(int maxDepth) {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new LLMServicePlugin(), Map.of("defaultModel", "fake")).await().join();
        LLMService llm = ctx.get(LLMService.NAME);
        llm.registerModel("fake", FINAL_MODEL);
        ctx.plugin(new AgentLoopPlugin(), null).await().join();
        ctx.plugin(new SubagentPlugin(), Map.of("maxDepth", maxDepth)).await().join();
        ctx.plugin(new SubagentToolPlugin(), null).await().join();
        return ctx;
    }

    /** Stack with parallelDelegates enabled and a custom parent model. */
    private static Context stackConfigured(int maxDepth, boolean parallel, ChatModel parent) {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new LLMServicePlugin(), Map.of("defaultModel", "parent")).await().join();
        LLMService llm = ctx.get(LLMService.NAME);
        llm.registerModel("parent", parent);
        llm.registerModel("slow", request -> {
            try {
                Thread.sleep(450);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ChatResponse.text("slow-result");
        });
        ctx.plugin(new AgentLoopPlugin(), java.util.Map.of(
                "maxDepth", maxDepth, "parallelDelegates", parallel)).await().join();
        ctx.plugin(new SubagentPlugin(), Map.of("maxDepth", maxDepth)).await().join();
        ctx.plugin(new SubagentToolPlugin(), null).await().join();
        return ctx;
    }

    @Test
    void delegationRunsAChildSessionAndReturnsItsFinalText() {
        Context ctx = stack(3);
        SessionService sessions = ctx.get(SessionService.NAME);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        assertThat(tools.specs()).extracting(spec -> spec.name()).containsExactly("delegate_task");

        io.majo.harness.tools.ToolResult result = tools.execute(
                io.majo.harness.tools.ToolCall.of("delegate_task", "{\"task\":\"draft a summary\"}"));
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).isEqualTo("child-result");

        // the child is a fresh session with its own durable turn
        assertThat(sessions.sessionIds()).hasSize(1);
        String child = sessions.sessionIds().get(0);
        assertThat(sessions.events(child)).extracting(SessionEvent::type).containsExactly(
                SessionEventType.TURN_START,
                SessionEventType.USER_MESSAGE,
                SessionEventType.REQUEST_HEADER,
                SessionEventType.ASSISTANT_MESSAGE,
                SessionEventType.TURN_END);
        // the structured payload names the child so the UI can link to it
        assertThat(result.data()).containsEntry("childSessionId", child);
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void delegationHonoursPerAgentModelAndSystemPrompt() throws Exception {
        Context ctx = stack(3);
        LLMService llm = ctx.get(LLMService.NAME);
        llm.registerModel("alt", request -> ChatResponse.text("alt-result"));
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);

        io.majo.harness.tools.ToolResult result = tools.execute(
                io.majo.harness.tools.ToolCall.of("delegate_task", MAPPER.writeValueAsString(
                        java.util.Map.of(
                                "task", "draft as alt",
                                "model", "alt",
                                "systemPrompt", "You are the alt child agent."))));
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).isEqualTo("alt-result");
        assertThat(result.data()).containsEntry("model", "alt");

        SessionService sessions = ctx.get(SessionService.NAME);
        String child = sessions.sessionIds().get(0);
        var header = sessions.events(child).stream()
                .filter(event -> event.type() == SessionEventType.REQUEST_HEADER)
                .findFirst()
                .orElseThrow();
        assertThat(header.fields().get(SessionEvent.FIELD_MODEL)).isEqualTo("alt");
        assertThat(header.fields().get(SessionEvent.FIELD_SYSTEM_PROMPT))
                .isEqualTo("You are the alt child agent.");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void parallelDelegatesRunChildrenConcurrently() throws Exception {
        // parent fans out two slow children on a "fanout" prompt
        String callA = MAPPER.writeValueAsString(Map.of("task", "child A", "model", "slow"));
        String callB = MAPPER.writeValueAsString(Map.of("task", "child B", "model", "slow"));
        ChatModel parent = request -> {
            boolean hasToolResult = request.messages().stream()
                    .anyMatch(message -> message.role() == io.majo.harness.llm.ChatRole.TOOL);
            String lastUser = null;
            for (io.majo.harness.llm.ChatMessage message : request.messages()) {
                if (message.role() == io.majo.harness.llm.ChatRole.USER) {
                    lastUser = message.content();
                }
            }
            if (lastUser != null && lastUser.startsWith("fanout") && !hasToolResult) {
                return ChatResponse.toolCalls(List.of(
                        io.majo.harness.tools.ToolCall.of("delegate_task", callA),
                        io.majo.harness.tools.ToolCall.of("delegate_task", callB)));
            }
            return ChatResponse.text("parent-done");
        };
        Context ctx = stackConfigured(3, true, parent);
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        SessionService sessions = ctx.get(SessionService.NAME);
        String parentSession = sessions.createSession();

        long started = System.nanoTime();
        String answer = loop.runTurn(parentSession, "fanout");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        // two 450ms children finished in parallel, not ~900ms serial
        assertThat(elapsedMs).isLessThan(700);
        assertThat(answer).isEqualTo("parent-done");
        assertThat(sessions.sessionIds()).hasSize(3);
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void delegateSpecRunsChildInScopedContext() throws Exception {
        Context ctx = stack(3);
        LLMService llm = ctx.get(LLMService.NAME);
        llm.registerModel("alt", request -> ChatResponse.text("alt-result"));
        SubagentService subagent = ctx.get(SubagentService.NAME);

        SubagentService.DelegationOutcome outcome = subagent.delegateSpec(
                "scoped task", new SubagentService.AgentSpec("alt", "You are the scoped child.", null));
        assertThat(outcome.answer()).isEqualTo("alt-result");

        SessionService sessions = ctx.get(SessionService.NAME);
        var header = sessions.events(outcome.childSessionId()).stream()
                .filter(event -> event.type() == SessionEventType.REQUEST_HEADER)
                .findFirst()
                .orElseThrow();
        assertThat(header.fields().get(SessionEvent.FIELD_MODEL)).isEqualTo("alt");
        assertThat(header.fields().get(SessionEvent.FIELD_SYSTEM_PROMPT))
                .isEqualTo("You are the scoped child.");
        // the shared context survives scope disposal
        assertThat(sessions.createSession()).isNotBlank();
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void delegateSpecHonoursMaxStepsCap() throws Exception {
        Context ctx = stack(3);
        LLMService llm = ctx.get(LLMService.NAME);
        String runawayArgs = MAPPER.writeValueAsString(Map.of("task", "keep going"));
        llm.registerModel("never", request -> ChatResponse.toolCalls(List.of(
                io.majo.harness.tools.ToolCall.of("delegate_task", runawayArgs))));
        SubagentService subagent = ctx.get(SubagentService.NAME);

        assertThatThrownBy(() -> subagent.delegateSpec(
                "runaway", new SubagentService.AgentSpec("never", null, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxSteps=1");
        assertThat(subagent.recentRuns().get(0).status()).isEqualTo("failed");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void recentRunsLogSuccessAndBlocked() {
        Context ctx = stack(3);
        SubagentService subagent = ctx.get(SubagentService.NAME);
        assertThat(subagent.recentRuns()).isEmpty();
        subagent.delegate("ok task");

        assertThat(subagent.recentRuns()).extracting(SubagentService.Delegation::status).containsExactly("done");
        assertThat(subagent.recentRuns().get(0)).satisfies(delegation -> {
            assertThat(delegation.task()).isEqualTo("ok task");
            assertThat(delegation.detail()).isEqualTo("child-result");
        });
        ctx.fiber().disposeAsync().join();

        Context blockedCtx = stack(0); // maxDepth 0: every delegation is blocked and logged
        SubagentService blocked = blockedCtx.get(SubagentService.NAME);
        assertThatThrownBy(() -> blocked.delegate("nested")).isInstanceOf(SubagentException.class);
        assertThat(blocked.recentRuns()).extracting(SubagentService.Delegation::status).containsExactly("blocked");
        assertThat(blocked.recentRuns().get(0).task()).isEqualTo("nested");
        blockedCtx.fiber().disposeAsync().join();
    }

    @Test
    void recursionDepthIsGuardedLoudly() {
        Context ctx = stack(0); // maxDepth 0: any delegation exceeds it
        SubagentService subagent = ctx.get(SubagentService.NAME);
        assertThatThrownBy(() -> subagent.delegate("nested"))
                .isInstanceOf(SubagentException.class)
                .hasMessageContaining("maxDepth 0");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void badArgumentsSurfaceAsErrorResults() {
        Context ctx = stack(3);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        io.majo.harness.tools.ToolResult bad = tools.execute(
                io.majo.harness.tools.ToolCall.of("delegate_task", "{}"));
        assertThat(bad.ok()).isFalse();
        assertThat(bad.visibleText()).contains("task");
        ctx.fiber().disposeAsync().join();
    }
}
