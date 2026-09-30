package io.majo.harness.goal;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.Map;

/**
 * Mounts {@link GoalService} as the {@code goal} plugin (needs the session
 * store and the agent loop — the domain rides the log, the driver rides the
 * loop's inbox and events).
 */
public final class GoalPlugin implements Plugin {

    public static final String NAME = "goal";

    @Override
    public Object apply(Context ctx, Object config) {
        io.majo.harness.session.SessionService sessions =
                ctx.get(io.majo.harness.session.SessionService.NAME);
        io.majo.harness.agent.loop.AgentLoopService loop =
                ctx.get(io.majo.harness.agent.loop.AgentLoopService.NAME);
        if (sessions == null || loop == null) {
            throw new IllegalStateException("goal: the session and agent-loop modules "
                    + "must be mounted before the goal plugin");
        }
        new GoalService(ctx, config, sessions, loop);
        return null;
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new java.util.HashMap<>();
        inject.put(io.majo.harness.session.SessionService.NAME, null);
        inject.put(io.majo.harness.agent.loop.AgentLoopService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
