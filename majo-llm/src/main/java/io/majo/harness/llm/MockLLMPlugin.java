package io.majo.harness.llm;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.HashMap;
import java.util.Map;

/**
 * Registers the {@link MockChatModel} demo provider on {@code ctx.llm} as the
 * {@code llm-mock} plugin. Declares {@code llm} as its injection so the loader
 * only activates it once the registry service exists; unregistering the plugin
 * reverts the model registration as an effect.
 */
public final class MockLLMPlugin implements Plugin {

    public static final String NAME = "llm-mock";

    @Override
    public Object apply(Context ctx, Object config) {
        LLMService llm = ctx.get(LLMService.NAME);
        return llm.registerModel(MockChatModel.MODEL_NAME, new MockChatModel());
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
