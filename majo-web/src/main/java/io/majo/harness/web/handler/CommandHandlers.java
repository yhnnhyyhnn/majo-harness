package io.majo.harness.web.handler;

import com.sun.net.httpserver.HttpExchange;
import io.majo.harness.boot.commands.CommandRegistry;
import io.majo.harness.llm.LLMService;
import io.majo.harness.plan.PlanState;
import io.majo.harness.session.SessionService;
import io.majo.harness.subagent.SubagentService;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.web.Http;
import io.majo.harness.web.WebApiModels;
import io.majo.harness.web.WebContext;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Host backend commands (/api/commands) and their builtin registrations. */
public final class CommandHandlers {

    private final WebContext ctx;
    private final PluginHandlers plugins;

    public CommandHandlers(WebContext ctx, PluginHandlers plugins) {
        this.ctx = ctx;
        this.plugins = plugins;
    }

    /** Registers the host's builtin backend commands (roadmap B1/#3). */
    public void registerBuiltins() {
        CommandRegistry commands = ctx.boot.ctx().get(CommandRegistry.NAME);
        if (commands == null) {
            return;
        }
        commands.register("status", "harness counters (sessions/plugins/tools/models)",
                (commandCtx, args) -> statusText());
        commands.register("workflow",
                "run a named workflow: /workflow [name [json-args]] | status | resume <runId>",
                (commandCtx, args) -> {
                    io.majo.harness.workflow.WorkflowService workflow =
                            ctx.boot.ctx().get(io.majo.harness.workflow.WorkflowService.NAME);
                    if (workflow == null) {
                        throw new IllegalArgumentException(
                                "workflow: the workflow module is not mounted in this profile");
                    }
                    List<String> argv = new java.util.ArrayList<>();
                    if (args.get("args") instanceof List<?> raw) {
                        for (Object item : raw) {
                            argv.add(String.valueOf(item));
                        }
                    }
                    if (!argv.isEmpty() && argv.get(0).equalsIgnoreCase("status")) {
                        StringBuilder runs = new StringBuilder("recent runs:");
                        for (io.majo.harness.workflow.WorkflowService.RunRecord record
                                : workflow.records()) {
                            runs.append("\n- ").append(record.runId).append("  ")
                                    .append(record.name).append("  [")
                                    .append(record.status).append("] ")
                                    .append(record.durationMs).append("ms");
                            if ("failed".equals(record.status)) {
                                runs.append("  (resume: /workflow resume ")
                                        .append(record.runId).append(")");
                            }
                        }
                        return workflow.records().isEmpty()
                                ? "no workflow runs recorded"
                                : runs.toString();
                    }
                    if (!argv.isEmpty() && argv.get(0).equalsIgnoreCase("resume")) {
                        if (argv.size() < 2) {
                            throw new IllegalArgumentException("workflow: pass a run id");
                        }
                        String summary = workflow.resume(argv.get(1));
                        return "workflow resumed, completed:\n" + summary;
                    }
                    if (argv.isEmpty()) {
                        StringBuilder list = new StringBuilder("workflows:");
                        for (String name : workflow.names()) {
                            io.majo.harness.workflow.WorkflowDefinition definition =
                                    workflow.definition(name);
                            list.append("\n- ").append(name);
                            if (definition != null && definition.description() != null) {
                                list.append(" — ").append(definition.description());
                            }
                        }
                        return workflow.names().isEmpty()
                                ? "no workflows found in the workflows/ directory"
                                : list.toString();
                    }
                    String sessionId = String.valueOf(args.get("session"));
                    if (sessionId.isBlank() || "null".equals(sessionId)) {
                        throw new IllegalArgumentException("workflow: pass the current session id");
                    }
                    String name = argv.get(0);
                    Map<String, String> runArgs = new java.util.LinkedHashMap<>();
                    if (argv.size() > 1) {
                        try {
                            Map<?, ?> parsed = Http.JSON.readValue(argv.get(1), Map.class);
                            parsed.forEach((key, value) -> runArgs.put(String.valueOf(key),
                                    String.valueOf(value)));
                        } catch (com.fasterxml.jackson.core.JacksonException e) {
                            throw new IllegalArgumentException(
                                    "workflow: args must be a JSON object: " + e.getMessage());
                        }
                    }
                    String summary = workflow.run(sessionId, name, runArgs);
                    return "workflow \"" + name + "\" completed:\n" + summary;
                });
        commands.register("delegate", "run a scoped child delegation (task, model?)", (commandCtx, args) -> {
            Object taskValue = args.get("task");
            if (taskValue == null || String.valueOf(taskValue).isBlank()) {
                throw new IllegalArgumentException("task must not be blank");
            }
            SubagentService subagent = ctx.boot.ctx().get(SubagentService.NAME);
            if (subagent == null) {
                throw new IllegalArgumentException("subagent service unavailable");
            }
            String model = args.get("model") == null ? null : String.valueOf(args.get("model"));
            SubagentService.DelegationOutcome outcome =
                    subagent.delegateConfigured(String.valueOf(taskValue), model, null);
            return outcome.childSessionId() + " → " + outcome.answer();
        });
        registerPlanCommand(commands);
        registerCompactCommand(commands);
    }

    /**
     * The {@code compact} host command (dsh /compact): summarizes the
     * session's history into a durable CONTEXT_COMPACTION event without
     * burning a model turn of the user's conversation.
     */
    private void registerCompactCommand(CommandRegistry commands) {
        commands.register("compact", "compact this session's context into a durable summary",
                (commandCtx, args) -> {
                    io.majo.harness.compaction.CompactionService compaction =
                            ctx.boot.ctx().get(io.majo.harness.compaction.CompactionService.NAME);
                    if (compaction == null) {
                        throw new IllegalArgumentException(
                                "compact: the compaction module is not mounted in this profile");
                    }
                    String sessionId = String.valueOf(args.get("session"));
                    if (sessionId == null || sessionId.isBlank() || "null".equals(sessionId)) {
                        throw new IllegalArgumentException("compact: pass the current session id");
                    }
                    String summary = compaction.compactNow(sessionId);
                    return summary == null
                            ? "nothing to compact — the session has no derived history"
                            : "compacted: " + summary;
                });
    }

    /**
     * The {@code plan} host command (dsh plan-mode): {@code /plan <task>}
     * activates plan mode for the session (the task text is spliced into the
     * next turn as model-visible context), {@code /plan off} deactivates,
     * bare {@code /plan} reports the state. The UI's composer chip rides the
     * same command.
     */
    private void registerPlanCommand(CommandRegistry commands) {
        commands.register("plan", "plan mode: /plan <task> arms it, /plan off clears, /plan inspects",
                (commandCtx, args) -> {
                    io.majo.harness.session.SessionService sessions =
                            ctx.boot.ctx().get(io.majo.harness.session.SessionService.NAME);
                    io.majo.harness.session.SessionProjections projections =
                            ctx.boot.ctx().get(io.majo.harness.session.SessionProjections.NAME);
                    if (sessions == null || projections == null
                            || !projections.has(io.majo.harness.plan.PlanState.KEY)) {
                        throw new IllegalArgumentException(
                                "plan: the plan module is not mounted in this profile");
                    }
                    PlanState plans = projections.require(PlanState.KEY);
                    String sessionId = String.valueOf(args.get("session"));
                    if (sessionId == null || sessionId.isBlank() || "null".equals(sessionId)) {
                        throw new IllegalArgumentException("plan: pass the current session id");
                    }
                    if (!sessions.sessionIds().contains(sessionId)) {
                        throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
                    }
                    List<String> argv = new java.util.ArrayList<>();
                    if (args.get("args") instanceof List<?> raw) {
                        for (Object item : raw) {
                            argv.add(String.valueOf(item));
                        }
                    }
                    if (argv.isEmpty()) {
                        PlanState.Snapshot snapshot = plans.snapshot(sessionId);
                        return snapshot.active()
                                ? "plan mode is ACTIVE: " + snapshot.plan()
                                : "plan mode is off";
                    }
                    if ("off".equalsIgnoreCase(argv.get(0))) {
                        sessions.append(sessionId, io.majo.harness.session.SessionEventType.PLAN_SET,
                                Map.of(
                                        io.majo.harness.session.SessionEvent.FIELD_ACTIVE, false,
                                        io.majo.harness.session.SessionEvent.FIELD_PLAN, ""));
                        return "plan mode off";
                    }
                    String task = String.join(" ", argv);
                    sessions.append(sessionId, io.majo.harness.session.SessionEventType.PLAN_SET,
                            Map.of(
                                    io.majo.harness.session.SessionEvent.FIELD_ACTIVE, true,
                                    io.majo.harness.session.SessionEvent.FIELD_PLAN, task));
                    // model-visible activation: injected without waking the loop
                    io.majo.harness.agent.loop.AgentLoopService loop =
                            ctx.boot.ctx().get(io.majo.harness.agent.loop.AgentLoopService.NAME);
                    if (loop != null) {
                        loop.inject(sessionId, "PLAN MODE active for this task: " + task
                                + " — draft a step-by-step plan first, then call exit_plan_mode"
                                + " for human review before implementing anything.");
                    }
                    return "plan mode armed — the next turn drafts a plan for review";
                });
    }

    private String statusText() {
        SessionService sessions = ctx.boot.ctx().get(SessionService.NAME);
        LLMService llm = ctx.boot.ctx().get(LLMService.NAME);
        ToolRegistry tools = ctx.boot.ctx().get(ToolRegistry.NAME);
        return "sessions=" + (sessions == null ? 0 : sessions.sessionIds().size())
                + " plugins=" + plugins.mountedCount()
                + " tools=" + (tools == null ? 0 : tools.specs().size())
                + " models=" + (llm == null ? 0 : llm.registeredModels().size());
    }

    public WebApiModels.CommandsIndex index() {
        CommandRegistry commands = ctx.boot.ctx().get(CommandRegistry.NAME);
        if (commands == null) {
            return new WebApiModels.CommandsIndex(List.of());
        }
        return new WebApiModels.CommandsIndex(commands.entries().stream()
                .map(entry -> new WebApiModels.CommandInfo(entry.name(), entry.description()))
                .toList());
    }

    public WebApiModels.CommandResult run(HttpExchange exchange, String name) throws IOException {
        CommandRegistry commands = ctx.boot.ctx().get(CommandRegistry.NAME);
        if (commands == null) {
            throw new IllegalArgumentException("commands service unavailable — mount the commands row");
        }
        Map<?, ?> request = Http.JSON.readValue(exchange.getRequestBody(), Map.class);
        Map<String, Object> args = new LinkedHashMap<>();
        if (request != null) {
            for (Map.Entry<?, ?> entry : request.entrySet()) {
                args.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        String output = commands.run(name, args);
        return new WebApiModels.CommandResult(output == null ? "" : output);
    }
}
