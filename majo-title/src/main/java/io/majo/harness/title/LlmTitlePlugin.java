package io.majo.harness.title;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.llm.LLMService;
import java.util.HashMap;
import java.util.Map;

/**
 * Registers the {@link LlmTitleProvider LLM} sole provider on
 * {@code ctx.sessionTitle} as the {@code session-title-llm} plugin — the
 * model-backed alternative to the heuristic row (sole-provider semantics:
 * mount this row <em>instead of</em> {@code session-title-heuristic}; a
 * profile carrying both fails loudly at boot).
 *
 * <p>Config: {@code {model: <registered model name>, maxChars: <n>,
 * backoffSeconds: <n>}} — {@code model} defaults to the LLM service's
 * default model; failed derivations back off per prompt so sidebar polling
 * never hammers the model.
 */
public final class LlmTitlePlugin implements Plugin {

    public static final String NAME = "session-title-llm";

    @Override
    public Object apply(Context ctx, Object config) {
        Map<?, ?> map = config instanceof Map<?, ?> m ? m : Map.of();
        String model = map.get("model") == null ? null : String.valueOf(map.get("model"));
        int maxChars = LlmTitleProvider.DEFAULT_MAX_CHARS;
        if (map.get("maxChars") instanceof Number number && number.intValue() > 0) {
            maxChars = number.intValue();
        }
        long backoff = LlmTitleProvider.DEFAULT_BACKOFF_MILLIS;
        if (map.get("backoffSeconds") instanceof Number number && number.longValue() > 0) {
            backoff = number.longValue() * 1000L;
        }
        SessionTitleService titles = ctx.get(SessionTitleService.NAME);
        LLMService llm = ctx.get(LLMService.NAME);
        Disposable registration =
                titles.registerProvider(new LlmTitleProvider(llm, model, maxChars, backoff));
        return registration;
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(SessionTitleService.NAME, null);
        inject.put(LLMService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
