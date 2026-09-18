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
import java.util.concurrent.CountDownLatch;
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

    /**
     * Phase B: consecutive {@code parallel: true} steps run concurrently —
     * the barrier calc only clears when all three children arrive, so a
     * sequential runner would blow the duration budget.
     */
    @Test
    void parallelStepsRunConcurrently(@TempDir Path dir) throws Exception {
        CountDownLatch barrier = new CountDownLatch(3);
        Tool barrierCalc = new Tool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.of("calc", "demo arithmetic");
            }

            @Override
            public ToolResult execute(ToolCall call) {
                barrier.countDown();
                try {
                    barrier.await(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.ok("barrier-ok", Map.of());
            }
        };
        Files.writeString(dir.resolve("fan.yml"), """
                name: fan
                steps:
                  - id: p1
                    kind: turn
                    prompt: "fan one"
                    parallel: true
                  - id: p2
                    kind: turn
                    prompt: "fan two"
                    parallel: true
                  - id: p3
                    kind: turn
                    prompt: "fan three"
                    parallel: true
                  - id: join
                    kind: turn
                    prompt: "joined"
                """);
        Context ctx = harness(dir, barrierCalc, request -> {
            // every child must pass through the barrier calc before finishing
            boolean sawTool = request.messages().stream()
                    .anyMatch(message -> message.role() == io.majo.harness.llm.ChatRole.TOOL);
            if (sawTool) {
                return io.majo.harness.llm.ChatResponse.text("done");
            }
            return io.majo.harness.llm.ChatResponse.toolCalls(
                    List.of(ToolCall.of("calc", "{}")));
        });
        SessionService sessions = awaitService(ctx, SessionService.NAME);
        WorkflowService workflow = awaitService(ctx, WorkflowService.NAME);

        String sessionId = sessions.createSession();
        String summary = workflow.run(sessionId, "fan", Map.of());

        assertThat(summary).isEqualTo("done");
        assertThat(barrier.getCount()).as("all three children reached the barrier")
                .isZero();
        WorkflowService.RunRecord record = workflow.records().get(0);
        assertThat(record.status).isEqualTo("completed");
        assertThat(record.durationMs)
                .as("parallel fan-out must clear a 3-way barrier in bounded time")
                .isLessThan(20_000);
        long okSteps = sessions.events(sessionId).stream()
                .filter(event -> event.type() == SessionEventType.WORKFLOW_STEP)
                .count();
        assertThat(okSteps).isEqualTo(4); // three parallel + the join step
        ctx.fiber().disposeAsync().join();
    }

    /** Phase B: a failed run is resumable in-process from its failed step. */
    @Test
    void failedRunsResumeInProcess(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("once.yml"), """
                name: once
                steps:
                  - id: only
                    kind: turn
                    prompt: "say something"
                """);
        java.util.concurrent.atomic.AtomicInteger modelCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        Context ctx = harness(dir, null, request -> {
            if (modelCalls.incrementAndGet() == 1) {
                throw new io.majo.harness.llm.ModelException("transient outage");
            }
            String expression = lastUserText(request.messages());
            return io.majo.harness.llm.ChatResponse.text("answer(" + expression + ")");
        });
        SessionService sessions = awaitService(ctx, SessionService.NAME);
        WorkflowService workflow = awaitService(ctx, WorkflowService.NAME);

        String sessionId = sessions.createSession();
        assertThatThrownBy(() -> workflow.run(sessionId, "once", Map.of()))
                .hasMessageContaining("transient outage");
        WorkflowService.RunRecord failed = workflow.records().get(0);
        assertThat(failed.status).isEqualTo("failed");

        // resume replays recorded args, skips nothing (the step never succeeded)
        String summary = workflow.resume(failed.runId);
        assertThat(summary).contains("answer(say something)");
        assertThat(failed.status).isEqualTo("completed");
        assertThat(modelCalls.get()).as("exactly one failed + one replayed call")
                .isEqualTo(2);

        // resuming a non-failed run is rejected
        assertThatThrownBy(() -> workflow.resume(failed.runId))
                .hasMessageContaining("nothing to resume");
        ctx.fiber().disposeAsync().join();
    }

    /** Loop + subagent + mock model: the child answers are deterministic. */
    private static Context harness(Path workflowDir) throws Exception {
        return harness(workflowDir, null, request -> {
            String expression = lastUserText(request.messages());
            return io.majo.harness.llm.ChatResponse.text("answer(" + expression + ")");
        });
    }

    private static Context harness(Path workflowDir, Tool extraCalc,
            io.majo.harness.llm.ChatModel model) throws Exception {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new io.majo.harness.session.SessionProjectionsPlugin(), null).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new LLMServicePlugin(), Map.of("defaultModel", "model")).await().join();
        LLMService llm = ctx.get(LLMService.NAME);
        llm.registerModel("model", model);
        // mirror the proven SubagentSeamTest stack: loop before subagent,
        // projections and the subagent tool consumer mounted alongside
        ctx.plugin(new io.majo.harness.agent.loop.AgentLoopPlugin(), null).await().join();
        ctx.plugin(new io.majo.harness.subagent.SubagentPlugin(), null).await().join();
        ctx.plugin(new io.majo.harness.subagent.SubagentToolPlugin(), null).await().join();
        // the child offers a marker tool so the mock produces a two-round
        // conversation ending in a deterministic final answer
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        tools.register(extraCalc != null ? extraCalc : new Tool() {
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
