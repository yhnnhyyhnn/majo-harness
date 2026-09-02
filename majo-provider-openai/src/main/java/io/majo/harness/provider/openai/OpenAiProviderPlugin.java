package io.majo.harness.provider.openai;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.llm.ChatModel;
import io.majo.harness.llm.LLMService;
import java.util.HashMap;
import java.util.Map;

/**
 * Registers an {@link OpenAiChatModel} on {@code ctx.llm} as the
 * {@code llm-openai} plugin — the bring-your-own-endpoint provider. The model
 * is validated before registration (config failures fail loud), then the
 * registration disposer is returned so the plugin fiber reverts it on unload.
 *
 * <p>Config keys are documented on {@link OpenAiChatModel}. The registry key
 * is {@code name} (defaults to {@code model}); point {@code llm.defaultModel}
 * at it in the profile.
 */
public final class OpenAiProviderPlugin implements Plugin {

    public static final String NAME = "llm-openai";

    @Override
    public Object apply(Context ctx, Object config) {
        LLMService llm = ctx.get(LLMService.NAME);
        ChatModel model = new OpenAiChatModel(config); // validates loudly
        String registryKey = registryKey(config);
        return llm.registerModel(registryKey, model);
    }

    private static String registryKey(Object config) {
        if (config instanceof Map<?, ?> map && map.get("name") != null) {
            return String.valueOf(map.get("name"));
        }
        if (config instanceof Map<?, ?> map && map.get("model") != null) {
            return String.valueOf(map.get("model"));
        }
        throw new IllegalArgumentException("llm-openai: config requires \"model\"");
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(LLMService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
