package io.majo.harness.title;

import io.majo.harness.session.SessionEvent;
import java.util.List;

/**
 * The shipped heuristic {@link SessionTitleProvider}: titles a session from
 * its first user message (whitespace collapsed, truncated at 60 chars with an
 * ellipsis); returns {@code null} for logs without user content. An
 * LLM-backed provider can replace it behind the same seam.
 */
public final class HeuristicSessionTitleProvider implements SessionTitleProvider {

    public static final String PROVIDER_NAME = "heuristic";
    static final int MAX_TITLE_LENGTH = 60;

    @Override
    public String title(List<SessionEvent> events) {
        for (SessionEvent event : events) {
            if (event.type() == io.majo.harness.session.SessionEventType.USER_MESSAGE
                    && event.content() != null) {
                String collapsed = event.content().replaceAll("\\s+", " ").strip();
                if (collapsed.isEmpty()) {
                    continue;
                }
                return collapsed.length() > MAX_TITLE_LENGTH
                        ? collapsed.substring(0, MAX_TITLE_LENGTH - 3) + "..."
                        : collapsed;
            }
        }
        return null;
    }
}
