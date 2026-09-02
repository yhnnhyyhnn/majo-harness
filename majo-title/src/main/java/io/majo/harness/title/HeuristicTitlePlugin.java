package io.majo.harness.title;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import java.util.HashMap;
import java.util.Map;

/**
 * Registers the {@link HeuristicSessionTitleProvider heuristic} sole provider
 * on {@code ctx.sessionTitle} as the {@code session-title-heuristic} plugin.
 * The disposer is returned so the provider clears when this plugin unloads.
 */
public final class HeuristicTitlePlugin implements Plugin {

    public static final String NAME = "session-title-heuristic";

    @Override
    public Object apply(Context ctx, Object config) {
        SessionTitleService titles = ctx.get(SessionTitleService.NAME);
        Disposable registration = titles.registerProvider(new HeuristicSessionTitleProvider());
        return registration;
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(SessionTitleService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
