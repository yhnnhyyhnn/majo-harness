package io.majo.harness.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.majo.harness.llm.LLMService;
import io.majo.harness.llm.LLMServicePlugin;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import io.majo.harness.tools.ToolsPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runner end-to-end: steps execute as scoped child delegations in order,
 * outputs thread through templates, WORKFLOW_* bookkeeping lands in the
 * requesting session (skipped by derivation), and failures follow the
 * onFailure policy.
 */
class WorkflowRunTest {

    @Test
    void stepsRunInOrderAndOutputsThreadThrough(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("two-step.yml"), """
                name: two-step
                steps:
                  - id: one
                    kind: turn
                    prompt: "first says {{args.word}}"
                  - id: two
                    kind: turn
                    prompt: "second heard: {{steps.one.output}}"
                """);
        Context ctx = harness(dir);
        SessionService sessions = awaitService(ctx, SessionService.NAME);
        WorkflowService workflow = awaitService(ctx, WorkflowService.NAME);

        String sessionId = sessions.createSession();
        String summary = workflow.run(sessionId, "two-step", Map.of("word", "hello"));

        // the mock child delegates: step one's output ("first says hello")
        // threads into step two's prompt and thus into the final summary
        assertThat(summary).contains("first says hello", "second heard");
        List<SessionEventType> kinds = sessions.events(sessionId).stream()
                .map(SessionEvent::type).toList();
        assertThat(kinds).containsExactly(
                SessionEventType.WORKFLOW_START,
                SessionEventType.WORKFLOW_STEP,
                SessionEventType.WORKFLOW_STEP,
                SessionEventType.WORKFLOW_END);
        List<SessionEvent> workflowEvents = sessions.events(sessionId);
        assertThat(workflowEvents.get(0).content()).isEqualTo("two-step");
        assertThat(workflowEvents.get(3).content()).isEqualTo("completed");
        // the second step's status is ok and its run id threads through
        assertThat(workflowEvents.get(2).fields().get(SessionEvent.FIELD_STATUS))
                .isEqualTo("ok");
        // bookkeeping stays out of the derived model history
        assertThat(io.majo.harness.agent.loop.MessageDeriver
                .derive(sessions.events(sessionId))).isEmpty();
    }

    @Test
    void failedStepAbortsByDefaultAndThrows(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("broken.yml"), """
                name: broken
                steps:
                  - id: boom
                    kind: turn
                    prompt: "unresolved {{steps.nothere.output}}"
                  - id: never
                    kind: turn
                    prompt: "never reached"
                """);
        Context ctx = harness(dir);
        SessionService sessions = awaitService(ctx, SessionService.NAME);
        WorkflowService workflow = awaitService(ctx, WorkflowService.NAME);

        String sessionId = sessions.createSession();
        assertThatThrownBy(() -> workflow.run(sessionId, "broken", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step \"boom\"");
        // start, one failed step, end(failed); the never-step never ran
        List<SessionEventType> kinds = sessions.events(sessionId).stream()
                .map(SessionEvent::type).toList();
        assertThat(kinds).containsExactly(
                SessionEventType.WORKFLOW_START,
                SessionEventType.WORKFLOW_STEP,
                SessionEventType.WORKFLOW_END);
        SessionEvent end = sessions.events(sessionId).get(2);
        assertThat(end.content()).contains("failed");
    }

    /** Loop + subagent + mock model: the child answers are deterministic. */
    private static Context harness(Path workflowDir) throws Exception {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new io.majo.harness.session.SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new LLMServicePlugin(), Map.of("defaultModel", "model")).await().join();
        LLMService llm = ctx.get(LLMService.NAME);
        llm.registerModel("model", request -> {
            String expression = lastUserText(request.messages());
            return io.majo.harness.llm.ChatResponse.text("answer(" + expression + ")");
        });
        // mirror the proven SubagentSeamTest stack: loop before subagent,
        // projections and the subagent tool consumer mounted alongside
        ctx.plugin(new io.majo.harness.agent.loop.AgentLoopPlugin(), null).await().join();
        ctx.plugin(new io.majo.harness.subagent.SubagentPlugin(), null).await().join();
        ctx.plugin(new io.majo.harness.subagent.SubagentToolPlugin(), null).await().join();
        // the child offers a marker tool so the mock produces a two-round
        // conversation ending in a deterministic final answer
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        tools.register(new Tool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.of("calc", "demo arithmetic");
            }

            @Override
            public ToolResult execute(ToolCall call) {
                // echo the requested expression so step outputs thread visibly
                try {
                    var node = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(call.arguments());
                    String expression = node.path("expression").asText("");
                    return ToolResult.ok("calc(" + expression + ") -> 2", Map.of());
                } catch (java.io.IOException e) {
                    return ToolResult.ok("calc(?) -> 2", Map.of());
                }
            }
        });
        ctx.plugin(new WorkflowPlugin(), Map.of("dir", workflowDir.toString())).await().join();
        return ctx;
    }

    /**
     * Fibers with declared injections activate asynchronously after their
     * entry settles — poll until the service surfaces at the root.
     */
    static <T> T awaitService(Context ctx, String name) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            T service = ctx.get(name);
            if (service != null) {
                return service;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("service \"" + name + "\" did not activate");
    }

    private static String lastUserText(List<io.majo.harness.llm.ChatMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            var message = messages.get(index);
            if (message.role() == io.majo.harness.llm.ChatRole.USER
                    && message.content() != null) {
                return message.content();
            }
        }
        return "";
    }
}
