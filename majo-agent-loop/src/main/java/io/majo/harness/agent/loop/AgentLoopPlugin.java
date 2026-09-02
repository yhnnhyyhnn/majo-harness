package io.majo.harness.agent.loop;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.llm.LLMService;
import io.majo.harness.session.SessionProjections;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.ToolRegistry;
import java.util.HashMap;
import java.util.Map;

/**
 * Mounts {@link AgentLoopService} as the {@code agent-loop} plugin. Declares
 * {@code sessions}, {@code tools}, {@code llm}, and {@code sessionProjections}
 * as injections: the loader only activates the loop once every capability
 * service is live, and removes it (reverting its registrations) when any of
 * them disappears. The plugin also contributes the {@link TurnSummary}
 * projection unit and returns its disposer so the unit reverts on unload.
 */
public final class AgentLoopPlugin implements Plugin {

    public static final String NAME = "agent-loop";

    @Override
    public Object apply(Context ctx, Object config) {
        new AgentLoopService(ctx, config);
        SessionProjections projections = ctx.get(SessionProjections.NAME);
        return projections.register(TurnSummary.KEY, new TurnSummary());
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(SessionService.NAME, null);
        inject.put(ToolRegistry.NAME, null);
        inject.put(LLMService.NAME, null);
        inject.put(SessionProjections.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
