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

    public ToolRegistry(Context ctx) {
        super(ctx, NAME);
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
                args -> runTool(tool, (ToolCall) args[0]));
        return (ToolResult) ctx.waterfall(null, ToolEvents.POST_EXECUTE, new Object[] {call, result},
                args -> args[1]);
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
