package io.majo.harness.web.handler;

import com.sun.net.httpserver.HttpExchange;
import io.majo.harness.subagent.SubagentService;
import io.majo.harness.web.Http;
import io.majo.harness.web.WebApiModels;
import io.majo.harness.web.WebContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Subagent delegation over the REST surface (/delegate family). */
public final class SubagentHandlers {

    private final WebContext ctx;

    public SubagentHandlers(WebContext ctx) {
        this.ctx = ctx;
    }

    /** Direct scoped delegation through the REST surface ({@code /delegate}). */
    public WebApiModels.DelegateResult delegate(HttpExchange exchange) throws IOException {
        SubagentService subagent = ctx.boot.ctx().get(SubagentService.NAME);
        if (subagent == null) {
            throw new IllegalArgumentException("subagent service unavailable — mount the subagent row");
        }
        Map<?, ?> request = Http.JSON.readValue(exchange.getRequestBody(), Map.class);
        Object taskValue = request.get("task");
        if (taskValue == null || String.valueOf(taskValue).isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        String task = String.valueOf(taskValue);
        String model = Http.stringValue(request.get("model"));
        String systemPrompt = Http.stringValue(request.get("systemPrompt"));
        Integer maxSteps = null;
        if (request.get("maxSteps") instanceof Number number) {
            maxSteps = number.intValue();
            if (maxSteps < 1) {
                throw new IllegalArgumentException("maxSteps must be >= 1");
            }
        }
        Boolean autoApprove;
        // a programmatic call has no human seat: default to auto-approve
        // unless the caller explicitly opts out
        if (request.get("autoApprove") instanceof Boolean bool) {
            autoApprove = bool;
        } else {
            autoApprove = true;
        }
        List<String> allowedTools = null;
        if (request.get("allowedTools") instanceof List<?> raw) {
            allowedTools = new ArrayList<>();
            for (Object item : raw) {
                allowedTools.add(String.valueOf(item));
            }
        }
        List<String> islands = null;
        if (request.get("islands") instanceof List<?> rawIslands) {
            islands = new ArrayList<>();
            for (Object item : rawIslands) {
                islands.add(String.valueOf(item));
            }
        }
        Map<String, String> settings = null;
        if (request.get("settings") instanceof Map<?, ?> rawSettings) {
            settings = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> item : rawSettings.entrySet()) {
                settings.put(String.valueOf(item.getKey()), String.valueOf(item.getValue()));
            }
        }
        boolean scopeOptions = maxSteps != null || autoApprove != null || allowedTools != null
                || islands != null || settings != null;
        SubagentService.DelegationOutcome outcome;
        if (scopeOptions) {
            SubagentService.AgentSpec spec = new SubagentService.AgentSpec(
                    model, systemPrompt, maxSteps, autoApprove, allowedTools, settings);
            outcome = islands != null
                    ? subagent.delegateSpecIslands(task, spec, islands)
                    : subagent.delegateSpec(task, spec);
        } else {
            outcome = subagent.delegateConfigured(task, model, systemPrompt);
        }
        return new WebApiModels.DelegateResult(outcome.childSessionId(), outcome.answer());
    }

    public WebApiModels.SubagentsIndex subagentsIndex() {
        SubagentService subagent = ctx.boot.ctx().get(SubagentService.NAME);
        if (subagent == null) {
            return new WebApiModels.SubagentsIndex(List.of());
        }
        return new WebApiModels.SubagentsIndex(subagent.recentRuns().stream()
                .map(run -> new WebApiModels.SubagentRun(
                        run.task(), run.status(), run.detail(), run.atMillis(),
                        run.model(), run.maxSteps(), run.autoApprove(), run.allowedTools()))
                .toList());
    }

    public Map<String, Object> islands() {
        SubagentService islandSubagent = ctx.boot.ctx().get(SubagentService.NAME);
        java.util.Set<String> names = islandSubagent == null ? java.util.Set.of()
                : islandSubagent.islandNames();
        return Map.of("islands", names);
    }
}
