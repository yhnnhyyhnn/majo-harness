package io.majo.harness.title;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.jcordis.core.util.Disposable;
import io.majo.harness.session.SessionService;

/**
 * The session-title service ({@code ctx.sessionTitle}): holds the single
 * {@link SessionTitleProvider} and derives a session's title from its durable
 * log. Registering a second provider fails loudly (sole-provider semantics);
 * generating without a provider fails loudly.
 */
public final class SessionTitleService extends Service {

    public static final String NAME = "sessionTitle";

    private final SessionService sessions;
    private volatile SessionTitleProvider provider;

    public SessionTitleService(Context ctx, SessionService sessions) {
        super(ctx, NAME);
        this.sessions = sessions;
    }

    /** Registers the sole title provider, failing loudly on duplicates. */
    public Disposable registerProvider(SessionTitleProvider titleProvider) {
        SessionTitleProvider previous = provider;
        if (previous != null) {
            throw new IllegalStateException("a session title provider is already registered");
        }
        provider = titleProvider;
        return () -> {
            if (provider == titleProvider) {
                provider = null;
            }
        };
    }

    /** Whether a title provider is registered. */
    public boolean hasProvider() {
        return provider != null;
    }

    /** The derived title of {@code sessionId}, or {@code null} when untitled. */
    public String title(String sessionId) {
        SessionTitleProvider active = provider;
        if (active == null) {
            throw new TitleException("no session title provider is registered");
        }
        return active.title(sessions.events(sessionId));
    }
}
