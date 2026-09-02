package io.majo.harness.session;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.HashMap;
import java.util.Map;

/**
 * Mounts {@link SessionProjections} as the {@code session-projections} plugin.
 * Declares {@code sessions} as its injection; the broadcast subscription it
 * opens is a fiber effect, so it reverts when this plugin unloads.
 */
public final class SessionProjectionsPlugin implements Plugin {

    public static final String NAME = "session-projections";

    @Override
    public Object apply(Context ctx, Object config) {
        SessionService sessions = ctx.get(SessionService.NAME);
        new SessionProjections(ctx, sessions);
        return null;
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(SessionService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
