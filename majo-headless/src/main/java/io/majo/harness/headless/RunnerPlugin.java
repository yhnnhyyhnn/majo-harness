package io.majo.harness.headless;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.agent.loop.AgentLoopService;
import io.majo.harness.session.SessionService;
import java.util.HashMap;
import java.util.Map;

/**
 * The one-shot run entry of a headless boot: config {@code {task: <text>}}
 * creates a session and drives one turn through {@code ctx.agentLoop}. Runs
 * only after the loop (and transitively every capability it needs) is active.
 */
public final class RunnerPlugin implements Plugin {

    public static final String NAME = "run";

    @Override
    public Object apply(Context ctx, Object config) {
        if (!(config instanceof Map<?, ?> map) || map.get("task") == null) {
            throw new IllegalArgumentException("run: config requires a \"task\" string");
        }
        String task = String.valueOf(map.get("task"));
        SessionService sessions = ctx.get(SessionService.NAME);
        AgentLoopService loop = ctx.get(AgentLoopService.NAME);
        String sessionId = sessions.createSession();
        loop.runTurn(sessionId, task);
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
