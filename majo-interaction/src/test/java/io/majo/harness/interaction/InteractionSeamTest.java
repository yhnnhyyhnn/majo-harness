package io.majo.harness.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.util.Disposable;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import io.majo.harness.tools.ToolsPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InteractionSeamTest {

    private static InteractionService interactions(Context root, Object config) {
        root.plugin(new InteractionPlugin(), config).await().join();
        return root.get(InteractionService.NAME);
    }

    private static final class CountingTool implements Tool {
        boolean executed;

        @Override
        public ToolSpec spec() {
            return ToolSpec.of("demo", "demo tool");
        }

        @Override
        public ToolResult execute(ToolCall call) {
            executed = true;
            return ToolResult.ok("ran");
        }
    }

    private static ToolRegistry gatedRegistry(Context root, Object approvalConfig, List<String> gated,
            CountingTool tool) {
        root.plugin(new ToolsPlugin(), null).await().join();
        root.plugin(new InteractionPlugin(), approvalConfig).await().join();
        root.plugin(new ToolApprovalPlugin(), Map.of("tools", gated)).await().join();
        ToolRegistry tools = root.get(ToolRegistry.NAME);
        tools.register(tool);
        return tools;
    }

    @Test
    void modesDecideApprovalsAndEmitEvents() {
        Context root = Context.create();
        List<ApprovalDecision> decisions = new ArrayList<>();
        root.on(InteractionService.EVENT_APPROVAL, (thisArg, args) -> {
            decisions.add((ApprovalDecision) args[1]);
            return null;
        });
        InteractionService auto = interactions(root, Map.of("approval", "auto"));
        assertThat(auto.approve(ApprovalRequest.of("run tool \"demo\"", "{}")))
                .isEqualTo(ApprovalDecision.APPROVE);

        Context denyRoot = Context.create();
        InteractionService deny = interactions(denyRoot, Map.of());
        assertThat(deny.approve(ApprovalRequest.of("run tool \"demo\"", "{}")))
                .isEqualTo(ApprovalDecision.DENY);
        assertThat(decisions).containsExactly(ApprovalDecision.APPROVE);
        denyRoot.fiber().disposeAsync().join();
        root.fiber().disposeAsync().join();
    }

    @Test
    void answersCannedOrFailLoudAndModesValidate() {
        Context root = Context.create();
        InteractionService canned = interactions(root, Map.of("approval", "auto", "answer", "canned:42"));
        assertThat(canned.ask(Question.ask("what is the answer?"))).isEqualTo("42");

        Context noneRoot = Context.create();
        InteractionService none = interactions(noneRoot, Map.of());
        assertThatThrownBy(() -> none.ask(Question.ask("hello?")))
                .isInstanceOf(InteractionException.class)
                .hasMessageContaining("no interaction handler");

        assertThatThrownBy(() -> interactions(Context.create(), Map.of("approval", "chaos")))
                .satisfies(t -> assertThat(rootCause(t)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("approval"));
        assertThatThrownBy(() -> interactions(Context.create(), Map.of("answer", "maybe")))
                .satisfies(t -> assertThat(rootCause(t)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("answer"));
        noneRoot.fiber().disposeAsync().join();
        root.fiber().disposeAsync().join();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor;
    }

    @Test
    void abstainingHandlersFallBackToDenyAndRegistrationIsExclusive() {
        Context root = Context.create();
        root.plugin(new InteractionPlugin(), Map.of("approval", "auto")).await().join();
        InteractionService service = root.get(InteractionService.NAME);
        assertThat(service.handlerNames()).containsExactly("approval-auto");

        assertThatThrownBy(() -> service.register("approval-auto",
                new ConfiguredInteractionHandler("x", ApprovalDecision.ABSTAIN, null)))
                .isInstanceOf(IllegalStateException.class);
        Disposable added = service.register("observer",
                new ConfiguredInteractionHandler("observer", ApprovalDecision.ABSTAIN, null));
        assertThat(service.handlerNames()).contains("approval-auto", "observer");
        added.dispose();
        assertThat(service.handlerNames()).containsExactly("approval-auto");
        root.fiber().disposeAsync().join();

        // an all-abstain handler set denies (fail-safe)
        Context abstainRoot = Context.create();
        InteractionService abstain = new InteractionService(abstainRoot);
        abstain.register("observer",
                new ConfiguredInteractionHandler("observer", ApprovalDecision.ABSTAIN, null));
        assertThat(abstain.approve(ApprovalRequest.of("x", ""))).isEqualTo(ApprovalDecision.DENY);
        abstainRoot.fiber().disposeAsync().join();
    }

    @Test
    void frontRegisteredHandlerDecidesBeforeFallbacks() {
        Context root = Context.create();
        root.plugin(new InteractionPlugin(), Map.of("approval", "deny")).await().join();
        InteractionService service = root.get(InteractionService.NAME);
        // the web UI registers at the front; its decision wins over the deny fallback
        service.registerFront("web-ui",
                new ConfiguredInteractionHandler("web-ui", ApprovalDecision.APPROVE, "canned"));
        assertThat(service.approve(ApprovalRequest.of("run tool \"demo\"", "{}")))
                .isEqualTo(ApprovalDecision.APPROVE);
        assertThat(service.ask(Question.ask("color?"))).isEqualTo("canned");
        root.fiber().disposeAsync().join();
    }

    @Test
    void queueingHandlerChannelsHumanDecisions() {
        QueueingInteractionHandler human = new QueueingInteractionHandler("human");
        human.submitApproval(true);
        assertThat(human.approve(ApprovalRequest.of("run tool \"demo\"", "{}")))
                .isEqualTo(ApprovalDecision.APPROVE);
        assertThat(human.pendingApprovals()).hasSize(1);

        human.submitAnswer("red");
        assertThat(human.answer(Question.ask("which color?"))).isEqualTo("red");
        assertThat(human.pendingQuestions()).hasSize(1);
    }

    @Test
    void approvalGateBlocksAndAllowsOnMode() {
        CountingTool tool = new CountingTool();
        Context root = Context.create();
        ToolRegistry auto = gatedRegistry(root, Map.of("approval", "auto"), List.of("demo"), tool);
        assertThat(auto.execute(ToolCall.of("demo", "{}")).ok()).isTrue();
        assertThat(tool.executed).isTrue();
        root.fiber().disposeAsync().join();

        CountingTool deniedTool = new CountingTool();
        Context denyRoot = Context.create();
        ToolRegistry deny = gatedRegistry(denyRoot, Map.of(), List.of("demo"), deniedTool);
        ToolResult denied = deny.execute(ToolCall.of("demo", "{}"));
        assertThat(denied.ok()).isFalse();
        assertThat(denied.visibleText()).contains("requires approval");
        assertThat(deniedTool.executed).isFalse();
        denyRoot.fiber().disposeAsync().join();

        // tools outside the gate list pass through untouched
        CountingTool ungatedTool = new CountingTool();
        Context passRoot = Context.create();
        ToolRegistry pass = gatedRegistry(passRoot, Map.of(), List.of("other"), ungatedTool);
        assertThat(pass.execute(ToolCall.of("demo", "{}")).ok()).isTrue();
        assertThat(ungatedTool.executed).isTrue();
        passRoot.fiber().disposeAsync().join();
    }
}
