package io.majo.harness.subagent;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.util.Disposables;
import java.util.HashMap;
import java.util.Map;

/**
 * The subagent tool consumer: registers {@code delegate_task} plus the
 * control family {@code send_message}/{@code list_agents}/
 * {@code interrupt_agent} (dsh tool-subagent-control parity) on
 * {@code ctx.tools} once the tools and subagent services are live.
 */
public final class SubagentToolPlugin implements Plugin {

    public static final String NAME = "subagent-tools";

    @Override
    public Object apply(Context ctx, Object config) {
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        SubagentService subagent = ctx.get(SubagentService.NAME);
        return Disposables.composite(
                tools.register(new DelegateTaskTool(subagent)),
                tools.register(new SendMessageTool(subagent)),
                tools.register(new ListAgentsTool(subagent)),
                tools.register(new InterruptAgentTool(subagent)));
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
