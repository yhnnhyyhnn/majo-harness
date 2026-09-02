package io.majo.harness.title;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.session.SessionService;
import java.util.HashMap;
import java.util.Map;

/**
 * Mounts {@link SessionTitleService} as the {@code session-title} plugin (the
 * registry side of the seam; the provider row supplies the sole provider).
 */
public final class SessionTitlePlugin implements Plugin {

    public static final String NAME = "session-title";

    @Override
    public Object apply(Context ctx, Object config) {
        SessionService sessions = ctx.get(SessionService.NAME);
        new SessionTitleService(ctx, sessions);
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
