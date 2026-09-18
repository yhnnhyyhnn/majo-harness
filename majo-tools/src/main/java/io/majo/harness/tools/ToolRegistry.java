package io.majo.harness.tools;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.jcordis.core.util.Disposable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The scoped tool registry ({@code ctx.tools}) plus its guarded execution
 * pipeline.
 *
 * <p>Registrations are reversible side effects: {@link #register} returns a
 * disposer that a plugin body returns from {@code apply}, so the plugin fiber
 * collects it and unregisters every tool when it unloads. Execution routes
 * through the {@link ToolEvents pre-execute} / {@link ToolEvents post-execute}
 * waterfalls so policy and observability plugins can rewrite, reject, or
 * observe without importing the registry.
 */
public final class ToolRegistry extends Service {

    public static final String NAME = "tools";

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    /**
     * Per-call deadline in millis (dsh guard timeout-policy analog); 0
     * disables. Hosts enable it in their profile — hung tool calls must
     * return a clear timed-out error instead of stalling the turn forever.
     */
    private final long timeoutMillis;
    private final java.util.concurrent.ExecutorService toolExecutor =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    public ToolRegistry(Context ctx) {
        this(ctx, Map.of());
    }

    public ToolRegistry(Context ctx, Object config) {
        super(ctx, NAME);
        long seconds = config instanceof Map<?, ?> map
                && map.get("toolTimeoutSeconds") instanceof Number number
                && number.longValue() > 0 ? number.longValue() : 0;
        this.timeoutMillis = seconds * 1000L;
    }

    /**
     * Registers a tool, failing loudly on duplicates. Plugin bodies return the
     * disposer so the registration reverts when the providing plugin unloads;
     * programmatic callers dispose it explicitly.
     */
    public Disposable register(Tool tool) {
        String toolName = tool.spec().name();
        Tool previous = tools.putIfAbsent(toolName, tool);
        if (previous != null) {
            throw new IllegalStateException("tool \"" + toolName + "\" has been registered");
        }
        return () -> tools.remove(toolName, tool);
    }

    /** Every registered tool spec (the schema set offered to the model). */
    public List<ToolSpec> specs() {
        return tools.values().stream().map(Tool::spec).toList();
    }

    /** Executes one model-requested call through the guarded pipeline. */
    public ToolResult execute(ToolCall call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.error("unknown tool \"" + call.name() + "\"");
        }
        Object result = ctx.waterfall(null, ToolEvents.PRE_EXECUTE, new Object[] {call, tool},
                args -> runToolBounded(tool, (ToolCall) args[0]));
        return (ToolResult) ctx.waterfall(null, ToolEvents.POST_EXECUTE, new Object[] {call, result},
                args -> args[1]);
    }

    /** Runs the tool under the configured deadline; the task is interrupted on expiry. */
    private ToolResult runToolBounded(Tool tool, ToolCall call) {
        if (timeoutMillis <= 0) {
            return runTool(tool, call);
        }
        java.util.concurrent.Future<ToolResult> future =
                toolExecutor.submit(() -> runTool(tool, call));
        try {
            return future.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            return ToolResult.error("tool \"" + call.name() + "\" timed out after "
                    + (timeoutMillis / 1000) + "s (toolTimeoutSeconds)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolResult.error("tool \"" + call.name() + "\" was interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            return ToolResult.error("tool \"" + call.name() + "\" threw " + e.getCause());
        }
    }

    private static ToolResult runTool(Tool tool, ToolCall call) {
        try {
            return tool.execute(call);
        } catch (RuntimeException e) {
            // an unexpected tool crash is a runtime outcome reported to the
            // model, not a framework failure
            return ToolResult.error("tool \"" + call.name() + "\" threw " + e);
        }
    }
}
