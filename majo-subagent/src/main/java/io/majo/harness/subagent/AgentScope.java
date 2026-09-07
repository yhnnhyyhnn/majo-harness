package io.majo.harness.subagent;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.agent.loop.AgentLoopService;
import java.util.Map;

/**
 * One scoped agent run as a plugin (jcordis guidance: mount an agent body as a
 * plugin so each agent/turn owns a fiber → independent teardown, service
 * rollback and shadow isolation).
 *
 * <p>{@code apply} extends the mounting context, isolates the
 * {@code agentLoop} key, mounts a fresh loop (lightweight plugin — no
 * projection re-registration), runs the child turn inside the scope, tears the
 * scoped fiber down and records the answer or failure. The harness mounts this
 * plugin via {@code ctx.plugin(...)} and awaits it.
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
    public Object apply(Context ctx, Object config) {
        Context scope = ctx.extend().isolate(AgentLoopService.NAME);
        Fiber scoped = scope.plugin(new ScopedLoopPlugin(), loopConfig());
        try {
            scoped.await().join();
            AgentLoopService loop = scope.get(AgentLoopService.NAME);
            if (loop == null) {
                throw new IllegalStateException("agent-scope: scoped agentLoop did not mount");
            }
            answer = loop.runTurn(childSessionId, task, null, spec.model(), null);
        } catch (RuntimeException e) {
            failure = unwrap(e);
            throw failure;
        } finally {
            scoped.disposeAsync().join();
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

    /** Mounts {@link AgentLoopService} only — no projection contribution. */
    private static final class ScopedLoopPlugin implements Plugin {
        @Override
        public Object apply(Context ctx, Object config) {
            new AgentLoopService(ctx, config);
            return null;
        }

        @Override
        public Map<String, Object> inject() {
            return Map.of();
        }

        @Override
        public String name() {
            return "agent-loop";
        }
    }
}
