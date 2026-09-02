package io.majo.harness.llm;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.jcordis.core.util.Disposable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The LLM registry service ({@code ctx.llm}): adapters register a
 * {@link ChatModel} under a model name; {@link #complete} resolves the request
 * and drives it. Registrations are reversible: a provider plugin body returns
 * the {@link #registerModel} disposer, so its model unregisters when the
 * plugin unloads.
 *
 * <p>Config: {@code {defaultModel: <name>}} selects the model used when a
 * request carries no explicit name. A completion with an unresolvable model
 * fails loudly.
 */
public final class LLMService extends Service {

    public static final String NAME = "llm";

    private final Map<String, ChatModel> models = new ConcurrentHashMap<>();
    private final String defaultModel;

    public LLMService(Context ctx, Object config) {
        super(ctx, NAME);
        this.defaultModel = config instanceof Map<?, ?> map && map.get("defaultModel") != null
                ? String.valueOf(map.get("defaultModel"))
                : null;
    }

    /**
     * Registers a model adapter under {@code name}. Plugin bodies return the
     * disposer so the registration reverts when the providing plugin unloads;
     * programmatic callers dispose it explicitly. Duplicates fail loudly.
     */
    public Disposable registerModel(String name, ChatModel model) {
        ChatModel previous = models.putIfAbsent(name, model);
        if (previous != null) {
            throw new IllegalStateException("model \"" + name + "\" has been registered");
        }
        return () -> models.remove(name, model);
    }

    /**
     * Resolves the model name of a request: {@code request.model()} or the
     * configured default. Fails loudly when neither is set.
     */
    public String modelNameOf(ChatRequest request) {
        String modelName = request.model() != null ? request.model() : defaultModel;
        if (modelName == null) {
            throw new IllegalStateException("no model selected: request carries no model and llm.defaultModel is unset");
        }
        return modelName;
    }

    /**
     * Completes one request against {@link #modelNameOf}, broadcasting
     * {@link LlmEvents#REQUEST} and {@link LlmEvents#RESPONSE} around the
     * adapter call.
     */
    public ChatResponse complete(ChatRequest request) {
        return completeInternal(request, null);
    }

    /**
     * Streaming completion: text deltas flow through {@code onText} as the
     * adapter produces them. Streaming models emit real tokens; plain models
     * fall back to one burst of the whole answer — callers cannot tell the
     * difference, so the UI path is uniform.
     */
    public ChatResponse completeStream(ChatRequest request, java.util.function.Consumer<String> onText) {
        if (onText == null) {
            onText = ignored -> {};
        }
        return completeInternal(request, onText);
    }

    private ChatResponse completeInternal(ChatRequest request,
            java.util.function.Consumer<String> onText) {
        String modelName = modelNameOf(request);
        ChatModel model = models.get(modelName);
        if (model == null) {
            throw new IllegalStateException("unknown model \"" + modelName + "\"; registered: " + models.keySet());
        }
        ctx.emit(LlmEvents.REQUEST, request, modelName);
        ChatResponse response;
        if (onText == null) {
            response = model.complete(request);
        } else if (model instanceof StreamingChatModel streaming) {
            response = streaming.completeStream(request, onText);
        } else {
            response = model.complete(request);
            if (response.content() != null) {
                onText.accept(response.content());
            }
        }
        ctx.emit(LlmEvents.RESPONSE, request, response, modelName);
        return response;
    }
}
