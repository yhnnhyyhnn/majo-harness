package io.majo.harness.web.handler;

import com.sun.net.httpserver.HttpExchange;
import io.majo.harness.llm.LLMService;
import io.majo.harness.settings.SettingsService;
import io.majo.harness.web.Http;
import io.majo.harness.web.WebApiModels;
import io.majo.harness.web.WebContext;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Default model selection (global) and its persisted preference. */
public final class ModelHandlers {

    private final WebContext ctx;

    public ModelHandlers(WebContext ctx) {
        this.ctx = ctx;
    }

    public WebApiModels.ModelState state() {
        LLMService llm = ctx.boot.service(LLMService.NAME);
        List<String> models = llm.registeredModels();
        String current = llm.currentDefault() != null && models.contains(llm.currentDefault())
                ? llm.currentDefault()
                : models.isEmpty() ? null : models.get(0);
        return new WebApiModels.ModelState(current, models);
    }

    public WebApiModels.ModelState set(HttpExchange exchange) throws IOException {
        Map<?, ?> request = Http.JSON.readValue(exchange.getRequestBody(), Map.class);
        Object modelValue = request.get("model");
        if (modelValue == null || String.valueOf(modelValue).isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        String model = String.valueOf(modelValue);
        LLMService llm = ctx.boot.service(LLMService.NAME);
        if (!llm.registeredModels().contains(model)) {
            throw new IllegalArgumentException("unknown model \"" + model + "\"");
        }
        llm.defaultModel(model);
        SettingsService settings = ctx.boot.ctx().get(SettingsService.NAME);
        if (settings != null) {
            settings.set("web.model", model);
        }
        return state();
    }

    /** Restores a persisted model choice ({@code settings.web.model}) if valid. */
    public void restorePreference() {
        SettingsService settings = ctx.boot.ctx().get(SettingsService.NAME);
        if (settings == null) {
            return;
        }
        String saved = settings.get("web.model");
        if (saved != null) {
            LLMService llm = ctx.boot.service(LLMService.NAME);
            if (llm.registeredModels().contains(saved)) {
                llm.defaultModel(saved);
            }
        }
    }
}
