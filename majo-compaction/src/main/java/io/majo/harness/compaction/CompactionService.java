package io.majo.harness.compaction;

import io.majo.harness.agent.loop.MessageDeriver;
import io.majo.harness.llm.ChatMessage;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.LLMService;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The compaction runtime ({@code ctx.compaction}, dsh compaction-basic):
 * when the derived model history grows past the token budget, the whole
 * history is summarized by the model itself and the summary lands as a
 * durable {@code CONTEXT_COMPACTION} event. Derivation restarts from the
 * summary — the log stays the single source of truth ("model-visible means
 * logged" holds: the summary IS in the log).
 *
 * <p>Token counts are estimated at ~4 characters per token plus a small
 * per-message overhead — a honest heuristic that needs no provider-specific
 * tokenizer.
 */
public final class CompactionService extends io.jcordis.core.service.Service {

    public static final String NAME = "compaction";
    public static final int DEFAULT_MAX_TOKENS = 32_000;
    public static final String SUMMARIZE_INSTRUCTION =
            "Summarize the conversation so far for a replacement context: keep key facts, "
                    + "decisions, user preferences, and open threads. Be concise but complete. "
                    + "Respond with the summary only.";
    static final Logger LOG = LoggerFactory.getLogger(CompactionService.class);

    private final SessionService sessions;
    private final LLMService llm;
    private final int maxTokens;

    public CompactionService(io.jcordis.core.context.Context ctx, SessionService sessions,
            LLMService llm, Object config) {
        super(ctx, NAME);
        this.sessions = sessions;
        this.llm = llm;
        int tokens = DEFAULT_MAX_TOKENS;
        if (config instanceof Map<?, ?> map && map.get("maxTokens") instanceof Number number) {
            tokens = number.intValue();
        }
        if (tokens < 100) {
            throw new IllegalArgumentException(
                    "compaction: maxTokens must be >= 100, got " + tokens);
        }
        this.maxTokens = tokens;
    }

    /** The configured pressure budget in (estimated) tokens. */
    public int budget() {
        return maxTokens;
    }

    /** Rough token estimate: ~4 characters per token plus per-message overhead. */
    public static int estimateTokens(List<ChatMessage> messages) {
        int chars = 0;
        for (ChatMessage message : messages) {
            chars += message.content() == null ? 0 : message.content().length();
            if (message.toolCalls() != null) {
                for (var call : message.toolCalls()) {
                    chars += call.name().length() + call.arguments().length();
                }
            }
        }
        return chars / 4 + messages.size() * 4;
    }

    /** The estimated token pressure of the session's current derived history. */
    public int estimateTokens(String sessionId) {
        return estimateTokens(MessageDeriver.derive(sessions.events(sessionId)));
    }

    /** Whether the session's derived history is over the budget. */
    public boolean isOverBudget(String sessionId) {
        return estimateTokens(sessionId) > maxTokens;
    }

    /**
     * Compacts when over budget; {@code true} when a summary was persisted.
     * Intended from the loop's before-request waterfall — the summary lands
     * in the log before the caller composes its request.
     */
    public boolean maybeCompact(String sessionId) {
        if (!isOverBudget(sessionId)) {
            return false;
        }
        String summary = compactNow(sessionId);
        if (summary != null) {
            LOG.info("compacted session \"{}\" into {} chars of summary", sessionId, summary.length());
            return true;
        }
        return false;
    }

    /**
     * Summarizes and persists unconditionally ({@code /compact}); returns the
     * summary, or {@code null} when there is no history worth compacting.
     */
    public String compactNow(String sessionId) {
        List<SessionEvent> events = sessions.events(sessionId);
        List<ChatMessage> derived = MessageDeriver.derive(events);
        if (derived.isEmpty()) {
            return null;
        }
        long upToSeq = events.isEmpty() ? 0 : events.get(events.size() - 1).seq();
        List<ChatMessage> summarizeRequest = new ArrayList<>(derived);
        summarizeRequest.add(ChatMessage.user(SUMMARIZE_INSTRUCTION));
        ChatResponse response = llm.complete(
                new ChatRequest(List.copyOf(summarizeRequest), List.of(), null));
        String summary = response.content() == null ? "" : response.content().trim();
        if (summary.isEmpty()) {
            LOG.warn("compaction: the model returned an empty summary for \"{}\"", sessionId);
            return null;
        }
        sessions.append(sessionId, SessionEventType.CONTEXT_COMPACTION, Map.of(
                io.majo.harness.session.SessionEvent.FIELD_CONTENT, summary,
                io.majo.harness.session.SessionEvent.FIELD_UP_TO_SEQ, upToSeq));
        return summary;
    }
}
