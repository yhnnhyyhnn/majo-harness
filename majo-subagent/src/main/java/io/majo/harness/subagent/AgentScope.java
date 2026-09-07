package io.majo.harness.subagent;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.agent.loop.AgentLoopService;
import java.util.Map;

/**
 * One scoped agent run as a plugin (jcordis agent-scope guidance): mounting
 * this plugin gives the run its own fiber/context — services registered on
 * that context roll back when the fiber is disposed and same-name services
 * shadow the root instead of colliding. The scoped {@code agentLoop} registers
 * directly on the plugin's context and is torn down with it; no extra
 * projection is contributed.
 */
public final class AgentScope implements Plugin {

    private final String childSessionId;
    private final String task;
    private final SubagentService.AgentSpec spec;

    private volatile String answer;
    private volatile RuntimeException failure;

    public AgentScope(String childSessionId, String task, SubagentService.AgentSpec spec) {
        this.childSessionId = childSessionId;
        this.task = task;
        this.spec = spec;
    }

    public String answer() {
        return answer;
    }

    public RuntimeException failure() {
        return failure;
    }

    @Override
    public String name() {
        return "agent-scope:" + childSessionId.substring(0, Math.min(8, childSessionId.length()));
    }

    @Override
    public Map<String, Object> inject() {
        return Map.of();
    }

    @Override
    public Object apply(Context scope, Object config) {
        // scope is the plugin fiber's own context: registering agentLoop here
        // shadows the root instance and is rolled back on fiber disposal
        try {
            new AgentLoopService(scope, loopConfig());
            AgentLoopService loop = scope.get(AgentLoopService.NAME);
            if (loop == null) {
                throw new IllegalStateException("agent-scope: scoped agentLoop did not register");
            }
            answer = loop.runTurn(childSessionId, task, null, spec.model(), null);
        } catch (RuntimeException e) {
            failure = unwrap(e);
            throw failure;
        }
        return null;
    }

    private Map<String, Object> loopConfig() {
        Map<String, Object> config = new java.util.HashMap<>();
        config.put("parallelDelegates", false);
        if (spec.systemPrompt() != null) {
            config.put("systemPrompt", spec.systemPrompt());
        }
        if (spec.maxSteps() != null) {
            config.put("maxSteps", spec.maxSteps());
        }
        return config;
    }

    private static RuntimeException unwrap(RuntimeException e) {
        if (e instanceof java.util.concurrent.CompletionException completion && completion.getCause() instanceof RuntimeException cause) {
            return cause;
        }
        return e;
    }
}
