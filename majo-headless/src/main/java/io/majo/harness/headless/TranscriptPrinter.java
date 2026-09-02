package io.majo.harness.headless;

import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import java.io.PrintStream;

/**
 * Shared transcript rendering for headless runs: prints every durable event of
 * every session plus the final answer line. Both {@link HeadlessMain} and the
 * CLI launcher use it, so presentation lives in one place.
 */
public final class TranscriptPrinter {

    private TranscriptPrinter() {}

    /** Prints all sessions of {@code sessions} to {@code out}. */
    public static void print(SessionService sessions, PrintStream out) {
        for (String sessionId : sessions.sessionIds()) {
            out.println("== session " + sessionId + " ==");
            String answer = null;
            for (SessionEvent event : sessions.events(sessionId)) {
                out.println("  " + event.seq() + " " + event.type() + " " + summarize(event));
                if (event.type() == SessionEventType.ASSISTANT_MESSAGE
                        && !event.fields().containsKey(SessionEvent.FIELD_TOOL_CALLS)) {
                    answer = event.content();
                }
            }
            out.println("answer: " + answer);
        }
    }

    private static String summarize(SessionEvent event) {
        if (event.content() != null) {
            return "content=\"" + event.content() + "\"";
        }
        if (event.fields().containsKey(SessionEvent.FIELD_TOOL_CALLS)) {
            return "toolCalls=" + event.fields().get(SessionEvent.FIELD_TOOL_CALLS);
        }
        if (event.type() == SessionEventType.REQUEST_HEADER) {
            return "model=\"" + event.fields().get(SessionEvent.FIELD_MODEL)
                    + "\" tools=" + event.fields().get(SessionEvent.FIELD_TOOL_NAMES)
                    + " systemPrompt=\"" + event.fields().get(SessionEvent.FIELD_SYSTEM_PROMPT) + "\"";
        }
        return event.fields().toString();
    }
}
