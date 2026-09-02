package io.majo.harness.title;

import io.majo.harness.session.SessionEvent;
import java.util.List;

/**
 * The session-title provider seam (Service Definition): derives a title for a
 * session from its durable events, or returns {@code null} when the log has
 * nothing worth titling. Exactly one provider is registered at a time (mirror
 * of dsh's sole {@code ctx.sessionTitle} provider).
 */
public interface SessionTitleProvider {

    /** The derived title, or {@code null} when the session is untitled. */
    String title(List<SessionEvent> events);
}
