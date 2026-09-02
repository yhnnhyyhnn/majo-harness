package io.majo.harness.subagent;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.agent.loop.AgentLoopService;
import io.majo.harness.session.SessionService;
import java.util.HashMap;
import java.util.Map;

/**
 * Mounts {@link SubagentService} as the {@code subagent} plugin. Declares the
 * agent loop and sessions as injections so delegation only activates once a
 * working loop exists.
 */
public final class SubagentPlugin implements Plugin {

    public static final String NAME = "subagent";

    @Override
    public Object apply(Context ctx, Object config) {
        new SubagentService(ctx, config);
        return null;
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(AgentLoopService.NAME, null);
        inject.put(SessionService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
