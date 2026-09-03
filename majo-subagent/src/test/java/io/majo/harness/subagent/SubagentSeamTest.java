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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubagentSeamTest {

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
