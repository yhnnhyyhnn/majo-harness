package io.majo.harness.llm;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/**
 * Mounts {@link LLMService} as the {@code llm} plugin (the registry side of
 * the LLM capability seam). Config: {@code {defaultModel: <name>}}.
 */
public final class LLMServicePlugin implements Plugin {

    public static final String NAME = "llm";

    @Override
    public Object apply(Context ctx, Object config) {
        new LLMService(ctx, config);
        return null;
    }

    @Override
    public String name() {
        return NAME;
    }
}
