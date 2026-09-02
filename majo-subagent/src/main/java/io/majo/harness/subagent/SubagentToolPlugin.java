package io.majo.harness.subagent;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.util.Disposables;
import java.util.HashMap;
import java.util.Map;

/**
 * The subagent tool consumer: registers the {@code delegate_task} tool on
 * {@code ctx.tools} once the tools and subagent services are live (further
 * consumers compose beside it via {@link Disposables}).
 */
public final class SubagentToolPlugin implements Plugin {

    public static final String NAME = "subagent-tools";

    @Override
    public Object apply(Context ctx, Object config) {
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        SubagentService subagent = ctx.get(SubagentService.NAME);
        Disposable delegate = tools.register(new DelegateTaskTool(subagent));
        return Disposables.composite(delegate);
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(ToolRegistry.NAME, null);
        inject.put(SubagentService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
