package io.majo.harness.web.handler;

import com.sun.net.httpserver.HttpExchange;
import io.majo.harness.llm.LLMService;
import io.majo.harness.session.SessionService;
import io.majo.harness.skill.SkillRegistry;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.web.Http;
import io.majo.harness.web.Metrics;
import io.majo.harness.web.Version;
import io.majo.harness.web.WebApiModels;
import io.majo.harness.web.WebContext;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Operator surface: health, metrics, info, OpenAPI descriptor. */
public final class OpsHandlers {

    private final WebContext ctx;
    private final PluginHandlers plugins;

    public OpsHandlers(WebContext ctx, PluginHandlers plugins) {
        this.ctx = ctx;
        this.plugins = plugins;
    }

    /** Liveness + counters for operators. */
    public WebApiModels.HealthInfo health() {
        SessionService sessions = ctx.boot.ctx().get(SessionService.NAME);
        LLMService llm = ctx.boot.ctx().get(LLMService.NAME);
        ToolRegistry tools = ctx.boot.ctx().get(ToolRegistry.NAME);
        return new WebApiModels.HealthInfo(true,
                (System.nanoTime() - ctx.startedNanos) / 1_000_000,
                Version.get(),
                sessions == null ? 0 : sessions.sessionIds().size(),
                plugins.mountedCount(),
                tools == null ? 0 : tools.specs().size(),
                llm == null ? 0 : llm.registeredModels().size(),
                ctx.requestCount.get(),
                ctx.errorCount.get());
    }

    public Map<String, Object> metricsSnapshot() {
        return Metrics.snapshot(ctx.startedNanos, ctx.requestCount.get(), ctx.errorCount.get());
    }

    public WebApiModels.Info info() {
        LLMService llm = ctx.boot.ctx().get(LLMService.NAME);
        List<String> models = llm == null ? List.of() : llm.registeredModels();
        ToolRegistry tools = ctx.boot.ctx().get(ToolRegistry.NAME);
        List<String> toolNames = tools == null ? List.of()
                : tools.specs().stream().map(spec -> spec.name()).sorted().toList();
        SkillRegistry skills = ctx.boot.ctx().get(SkillRegistry.NAME);
        int skillCount = skills == null ? 0 : skills.skills().size();
        return new WebApiModels.Info(Version.get(), models, toolNames, skillCount);
    }

    /** Serves the curated OpenAPI descriptor ({@code openapi.json} resource). */
    public void openApiSpec(HttpExchange exchange) throws IOException {
        byte[] payload = Http.resource("openapi.json");
        if (payload == null) {
            Http.json(exchange, 404, Map.of("error", "openapi.json missing"));
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
    }
}
