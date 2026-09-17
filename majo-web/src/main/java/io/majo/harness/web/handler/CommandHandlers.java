package io.majo.harness.web.handler;

import com.sun.net.httpserver.HttpExchange;
import io.majo.harness.boot.commands.CommandRegistry;
import io.majo.harness.llm.LLMService;
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
