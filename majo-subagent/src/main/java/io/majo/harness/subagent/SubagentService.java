package io.majo.harness.subagent;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.majo.harness.agent.loop.AgentLoopService;
import io.majo.harness.session.SessionService;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The subagent service ({@code ctx.subagent}): delegation into a child agent.
 * {@link #delegate} opens a fresh child session and drives it through the same
 * {@code ctx.agentLoop} — a child agent is a new session with isolated history,
 * not a new process or a second loop. Nesting depth is guarded against
 * unbounded recursion (config {@code {maxDepth: <n>}}, default 3); exceeding
 * it fails loudly.
 *
 * <p>Delegations are synchronous today (single-threaded turns); per-agent
 * contexts for concurrent children arrive with the agent-context milestone.
 */
public final class SubagentService extends Service {

    public static final String NAME = "subagent";
    public static final int DEFAULT_MAX_DEPTH = 3;

    private final AgentLoopService loop;
    private final SessionService sessions;
    private final Context root;
    private final int maxDepth;
    private final AtomicInteger depth = new AtomicInteger();

    /** Bounded recent-delegation log surfaced to UI ({@code /api/subagents}). */
    private static final int MAX_RECENT = 25;
    private final ArrayDeque<Delegation> recent = new ArrayDeque<>();

    /** One delegation attempt as shown in the Subagents panel. */
    public record Delegation(String task, String status, String detail, long atMillis,
            String model, Integer maxSteps, Boolean autoApprove,
            java.util.List<String> allowedTools) {}

    /** A finished delegation: the child session (for transcripts/UI links) + text. */
    public record DelegationOutcome(String childSessionId, String answer) {}

    /**
     * Per-agent configuration for a scoped child run (M-C1): optional model,
     * system prompt, a max-steps cap, an auto-approve policy, an
     * allowed-tool whitelist and per-scope settings overrides ({@code null}
     * fields inherit harness defaults).
     */
    public record AgentSpec(String model, String systemPrompt, Integer maxSteps,
            Boolean autoApprove, java.util.List<String> allowedTools,
            java.util.Map<String, String> settings) {

        public AgentSpec(String model, String systemPrompt, Integer maxSteps, Boolean autoApprove,
                java.util.List<String> allowedTools) {
            this(model, systemPrompt, maxSteps, autoApprove, allowedTools, null);
        }

        public AgentSpec(String model, String systemPrompt, Integer maxSteps, Boolean autoApprove) {
            this(model, systemPrompt, maxSteps, autoApprove, null, null);
        }

        public AgentSpec(String model, String systemPrompt, Integer maxSteps) {
            this(model, systemPrompt, maxSteps, null, null, null);
        }
    }

    public SubagentService(Context ctx, Object config) {
        super(ctx, NAME);
        this.root = ctx;
        this.loop = require(ctx, AgentLoopService.NAME);
        this.sessions = require(ctx, SessionService.NAME);
        int max = DEFAULT_MAX_DEPTH;
        if (config instanceof java.util.Map<?, ?> map && map.get("maxDepth") instanceof Number number) {
            max = number.intValue();
        }
        if (max < 0) {
            throw new IllegalArgumentException("subagent: maxDepth must be >= 0, got " + max);
        }
        this.maxDepth = max;
        // allowlist backstop: scoped agents may only run the tools their spec
        // grants; root turns (no label) or unconstrained scopes pass through
        ctx.on(io.majo.harness.tools.ToolEvents.PRE_EXECUTE, (thisArg, args) -> {
            String agent = io.majo.harness.interaction.InteractionContext.agent();
            java.util.List<String> allowed = io.majo.harness.interaction.InteractionContext.allowedTools();
            @SuppressWarnings("unchecked")
            java.util.function.Supplier<Object> next =
                    (java.util.function.Supplier<Object>) args[args.length - 1];
            if (agent == null || allowed == null) {
                return next.get();
            }
            io.majo.harness.tools.ToolCall call = (io.majo.harness.tools.ToolCall) args[0];
            if (!allowed.contains(call.name())) {
                return io.majo.harness.tools.ToolResult.error("tool \"" + call.name()
                        + "\" is not allowed for agent " + agent);
            }
            return next.get();
        });
    }

    private static <T> T require(Context ctx, String name) {
        T value = ctx.get(name);
        if (value == null) {
            throw new IllegalStateException(
                    "subagent: service \"" + name + "\" unavailable — declare it as an injection");
        }
        return value;
    }

    /**
     * Delegates {@code task} to a child session and returns its final text.
     * Recursive delegations nested deeper than {@code maxDepth} fail loudly.
     */
    public String delegate(String task) {
        return delegateWithChild(task).answer();
    }

    /** Like {@link #delegate}, also returning the child session id. */
    public DelegationOutcome delegateWithChild(String task) {
        return delegateConfigured(task, null, null);
    }

    /**
     * Delegates with explicit per-turn model/system prompt on the root loop
     * (fast path). Child {@code REQUEST_HEADER} events record what was used.
     */
    public DelegationOutcome delegateConfigured(String task, String model, String systemPrompt) {
        return guarded(task, new AgentSpec(model, systemPrompt, null), false);
    }

    /**
     * Runs the child turn inside a scoped jcordis context subtree (M-C1): a
     * fresh {@code agentLoop} instance is mounted on an isolated child context
     * with the spec's config, then disposed when the turn ends.
     */
    public DelegationOutcome delegateSpec(String task, AgentSpec spec) {
        return delegateSpec(task, spec, java.util.List.of());
    }

    /**
     * M-C4: like {@link #delegateSpec} but mounts {@code islands} (plugins)
     * inside the agent scope before the turn; their registrations roll back
     * with the scope.
     */
    public DelegationOutcome delegateSpec(String task, AgentSpec spec,
            java.util.List<AgentScope.Island> islands) {
        return guarded(task,
                spec == null ? new AgentSpec(null, null, null, null, null, null) : spec,
                true, islands);
    }

    private DelegationOutcome guarded(String task, AgentSpec spec, boolean scoped) {
        return guarded(task, spec, scoped, java.util.List.of());
    }

    private DelegationOutcome guarded(String task, AgentSpec spec, boolean scoped,
            java.util.List<AgentScope.Island> islands) {
        int entered = depth.incrementAndGet();
        try {
            if (entered > maxDepth) {
                SubagentException blocked = new SubagentException("subagent: delegation depth " + entered
                        + " exceeds maxDepth " + maxDepth);
                record(new Delegation(task, "blocked", blocked.getMessage(),
                        System.currentTimeMillis(), spec.model(), spec.maxSteps(),
                        spec.autoApprove(), spec.allowedTools()));
                throw blocked;
            }
            String childSessionId = sessions.createSession();
            try {
                String agent = "subagent-" + childSessionId.substring(0, Math.min(8, childSessionId.length()));
                boolean auto = spec.autoApprove() != null && spec.autoApprove();
                String answer = io.majo.harness.interaction.InteractionContext.run(agent, auto,
                        spec.allowedTools(),
                        () -> io.majo.harness.settings.SettingsService.scoped(spec.settings(),
                                () -> scoped ? runScoped(childSessionId, task, spec, islands)
                                        : loop.runTurn(childSessionId, task, null,
                                                spec.model(), spec.systemPrompt())));
                record(new Delegation(task, "done", preview(answer), System.currentTimeMillis(),
                        spec.model(), spec.maxSteps(), spec.autoApprove(), spec.allowedTools()));
                return new DelegationOutcome(childSessionId, answer);
            } catch (RuntimeException failure) {
                record(new Delegation(task, "failed", String.valueOf(failure.getMessage()),
                        System.currentTimeMillis(), spec.model(), spec.maxSteps(),
                        spec.autoApprove(), spec.allowedTools()));
                throw failure;
            }
        } finally {
            depth.decrementAndGet();
        }
    }

    /** Runs the child turn inside a plugin-mounted {@link AgentScope}. */
    private String runScoped(String childSessionId, String task, SubagentService.AgentSpec spec,
            java.util.List<AgentScope.Island> islands) {
        AgentScope agent = new AgentScope(childSessionId, task, spec, islands);
        // 1.0.1 semantics: shadowing a root service requires an isolated child
        // context; the plugin fiber owns the scope and rolls registrations back
        Context scope = root.extend().isolate(AgentLoopService.NAME);
        io.jcordis.core.fiber.Fiber fiber = scope.plugin(agent);
        try {
            fiber.await().join();
            if (agent.failure() != null) {
                throw agent.failure();
            }
            return agent.answer();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        } finally {
            fiber.disposeAsync().join();
        }
    }

    /** Newest-first snapshot of recent delegations (never fails when empty). */
    public synchronized List<Delegation> recentRuns() {
        return List.copyOf(recent);
    }

    private synchronized void record(Delegation delegation) {
        recent.addFirst(delegation);
        while (recent.size() > MAX_RECENT) {
            recent.removeLast();
        }
    }

    private static String preview(String text) {
        if (text == null) {
            return null;
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }
}
