package io.majo.harness.title;

import io.majo.harness.llm.ChatMessage;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.LLMService;
import io.majo.harness.llm.ModelException;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The LLM-backed {@link SessionTitleProvider} (dsh parity for the heuristic
 * default): asks the model for a short title from the session's first user
 * message. The title seam memoizes successful derivations, so this costs one
 * model call per session; a failed call backs off per prompt (the sidebar
 * polls untitled sessions every cycle, and the model must not be hammered),
 * returning {@code null} so the derivation retries after the backoff.
 */
public final class LlmTitleProvider implements SessionTitleProvider {

    public static final String PROVIDER_NAME = "llm";
    public static final String INSTRUCTION =
            "Propose a short title (at most 8 words) for a conversation that starts with "
                    + "the following message. Reply with the title only, no quotes.";

    static final int DEFAULT_MAX_CHARS = 60;
    static final long DEFAULT_BACKOFF_MILLIS = 60_000;

    private final LLMService llm;
    private final String model;
    private final int maxChars;
    private final long backoffMillis;
    /** Prompt (input) → epoch ms of the last failed derivation for it. */
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();

    public LlmTitleProvider(LLMService llm, String model, int maxChars, long backoffMillis) {
        this.llm = llm;
        this.model = model;
        this.maxChars = maxChars;
        this.backoffMillis = backoffMillis;
    }

    @Override
    public String title(List<SessionEvent> events) {
        String first = firstUserMessage(events);
        if (first == null) {
            return null;
        }
        Long failed = failedAt.get(first);
        if (failed != null && System.currentTimeMillis() - failed < backoffMillis) {
            return null; // backing off; retry after the window
        }
        String title;
        try {
            ChatResponse response = llm.complete(new ChatRequest(
                    List.of(ChatMessage.user(INSTRUCTION + "\n\n" + first)),
                    List.of(), model));
            title = response.content() == null ? "" : response.content().trim();
        } catch (ModelException e) {
            failedAt.put(first, System.currentTimeMillis());
            return null;
        }
        if (title.isEmpty()) {
            failedAt.put(first, System.currentTimeMillis());
            return null;
        }
        failedAt.remove(first);
        return title.length() > maxChars ? title.substring(0, maxChars - 3) + "..." : title;
    }

    /** The first non-blank user message, or {@code null}. */
    static String firstUserMessage(List<SessionEvent> events) {
        for (SessionEvent event : events) {
            if (event.type() == SessionEventType.USER_MESSAGE && event.content() != null) {
                String content = event.content().strip();
                if (!content.isEmpty()) {
                    return content;
                }
            }
        }
        return null;
    }
}
