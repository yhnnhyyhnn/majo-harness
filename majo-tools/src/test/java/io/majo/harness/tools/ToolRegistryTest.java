package io.majo.harness.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    private static final ToolSpec SPEC =
            new ToolSpec("demo", "demo tool", ToolSpec.of("demo", "demo tool").parameters());

    private static ToolRegistry registry(Context root) {
        root.plugin(new ToolsPlugin(), null).await().join();
        return root.get(ToolRegistry.NAME);
    }

    @Test
    void registerExecuteAndDispose() {
        Context root = Context.create();
        ToolRegistry tools = registry(root);
        Disposable registration = tools.register(new Tool() {
            @Override
            public ToolSpec spec() {
                return SPEC;
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.ok("done:" + call.name());
            }
        });

        ToolResult result = tools.execute(ToolCall.of("demo", "{}"));
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).isEqualTo("done:demo");
        assertThat(tools.specs()).extracting(ToolSpec::name).containsExactly("demo");

        registration.dispose();
        assertThat(tools.specs()).isEmpty();
        assertThat(tools.execute(ToolCall.of("demo", "{}")).visibleText())
                .isEqualTo("unknown tool \"demo\"");
        root.fiber().disposeAsync().join();
    }

    @Test
    void duplicateRegistrationFailsLoudAndPluginUnloadReverts() {
        Context root = Context.create();
        ToolRegistry tools = registry(root);
        Tool tool = new Tool() {
            @Override
            public ToolSpec spec() {
                return SPEC;
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.ok("x");
            }
        };
        tools.register(tool);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tools.register(tool))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tools.specs()).hasSize(1);
        root.fiber().disposeAsync().join();
        ToolRegistry afterUnload = root.get(ToolRegistry.NAME);
        assertThat(afterUnload).isNull();
    }

    @Test
    void toolCrashBecomesErrorResult() {
        Context root = Context.create();
        ToolRegistry tools = registry(root);
        tools.register(new Tool() {
            @Override
            public ToolSpec spec() {
                return SPEC;
            }

            @Override
            public ToolResult execute(ToolCall call) {
                throw new IllegalStateException("boom");
            }
        });
        ToolResult result = tools.execute(ToolCall.of("demo", "{}"));
        assertThat(result.ok()).isFalse();
        assertThat(result.visibleText()).contains("boom");
        root.fiber().disposeAsync().join();
    }

    @Test
    void preExecuteListenerCanRewriteOrReject() {
        Context root = Context.create();
        ToolRegistry tools = registry(root);
        AtomicBoolean executed = new AtomicBoolean();
        tools.register(new Tool() {
            @Override
            public ToolSpec spec() {
                return SPEC;
            }

            @Override
            public ToolResult execute(ToolCall call) {
                executed.set(true);
                return ToolResult.ok("ran:" + call.arguments());
            }
        });

        List<String> observed = new ArrayList<>();
        Disposable listener = root.on(ToolEvents.PRE_EXECUTE, (thisArg, args) -> {
            observed.add(((ToolCall) args[0]).name());
            @SuppressWarnings("unchecked")
            java.util.function.Supplier<Object> next = (java.util.function.Supplier<Object>) args[args.length - 1];
            return next.get();
        });
        // rewrite the arguments before delegating
        Disposable rewriter = root.on(ToolEvents.PRE_EXECUTE, (thisArg, args) -> {
            args[0] = new ToolCall(((ToolCall) args[0]).id(), ((ToolCall) args[0]).name(), "rewritten");
            @SuppressWarnings("unchecked")
            java.util.function.Supplier<Object> next = (java.util.function.Supplier<Object>) args[args.length - 1];
            return next.get();
        });

        ToolResult result = tools.execute(ToolCall.of("demo", "original"));
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).isEqualTo("ran:rewritten");
        assertThat(executed.get()).isTrue();
        assertThat(observed).containsExactly("demo");

        // a listener that returns without next() rejects execution outright
        Disposable policy = root.on(ToolEvents.PRE_EXECUTE, (thisArg, args) -> ToolResult.error("blocked by policy"));
        executed.set(false);
        ToolResult rejected = tools.execute(ToolCall.of("demo", "x"));
        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.visibleText()).isEqualTo("blocked by policy");
        assertThat(executed.get()).isFalse();

        listener.dispose();
        rewriter.dispose();
        policy.dispose();
        root.fiber().disposeAsync().join();
    }
}
