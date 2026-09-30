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
    /**
     * Safety buffer (dsh `headroomTokens` analog): compaction triggers when
     * estimated input tokens + headroom exceed the budget, so the model's
     * response never pushes the total past the context window. Default
     * 8,192 (proportional to majo's 32k budget; dsh uses 65,536 against a
     * 128k window).
     */
    public static final int DEFAULT_HEADROOM_TOKENS = 8_192;
    /** Tool results older than the final assistant round collapse past this many characters (dsh compaction-tool-result-pruner: thresholdChars). */
    public static final int DEFAULT_PRUNE_CHARS = 8_192;
    /** Kept head/tail of a pruned result (dsh: head 4096 / tail 1024). */
    public static final int DEFAULT_PRUNE_HEAD_CHARS = 4_096;
    public static final int DEFAULT_PRUNE_TAIL_CHARS = 1_024;
    public static final String SUMMARIZE_INSTRUCTION =
            "Summarize the conversation so far for a replacement context: keep key facts, "
                    + "decisions, user preferences, and open threads. Be concise but complete. "
                    + "Respond with the summary only.";
    static final Logger LOG = LoggerFactory.getLogger(CompactionService.class);

    private final SessionService sessions;
    private final LLMService llm;
    private final int maxTokens;
    private final int headroomTokens;
    private final int retainTokens;
    private final int pruneChars;
    private final int pruneHeadChars;
    private final int pruneTailChars;

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
        this.headroomTokens = intConfig(config, "headroomTokens", DEFAULT_HEADROOM_TOKENS);
        // the priced tail: default = a quarter of the budget (dsh region
        // retention), floored so tiny test budgets still keep a tail
        int retain = intConfig(config, "retainTokens", Math.max(64, maxTokens / 4));
        this.retainTokens = Math.min(retain, maxTokens);
        int prune = DEFAULT_PRUNE_CHARS;
        if (config instanceof Map<?, ?> map && map.get("pruneChars") instanceof Number number) {
            prune = number.intValue();
        }
        if (prune < 0) {
            throw new IllegalArgumentException(
                    "compaction: pruneChars must be >= 0, got " + prune);
        }
        this.pruneChars = prune;
        this.pruneHeadChars = intConfig(config, "headChars", DEFAULT_PRUNE_HEAD_CHARS);
        this.pruneTailChars = intConfig(config, "tailChars", DEFAULT_PRUNE_TAIL_CHARS);
    }

    private static int intConfig(Object config, String key, int fallback) {
        if (config instanceof Map<?, ?> map && map.get(key) instanceof Number number
                && number.intValue() >= 0) {
            return number.intValue();
        }
        return fallback;
    }

    /** The configured pressure budget in (estimated) tokens. */
    public int budget() {
        return maxTokens;
    }

    /** The priced-tail retention (tokens) the summary leaves verbatim. */
    public int retainTokens() {
        return retainTokens;
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

    /** The configured per-tool-result prune threshold in characters. */
    public int pruneChars() {
        return pruneChars;
    }

    /**
     * Tool-result pruning (dsh compaction-tool-result-pruner shape): in
     * <em>derived</em> history, tool results older than the final assistant
     * round collapse past the prune threshold to a marker line plus the
     * result's kept head and tail (defaults 4096/1024); the newest round
     * stays intact. The durable log is untouched — pruning is a
     * deterministic function of the log, so "model-visible means logged"
     * still holds.
     */
    public List<ChatMessage> pruneToolResults(List<ChatMessage> history) {
        int lastAssistant = -1;
        for (int index = 0; index < history.size(); index++) {
            if (history.get(index).role() == io.majo.harness.llm.ChatRole.ASSISTANT) {
                lastAssistant = index;
            }
        }
        List<ChatMessage> pruned = null;
        for (int index = 0; index < history.size(); index++) {
            ChatMessage message = history.get(index);
            if (index >= lastAssistant
                    || message.role() != io.majo.harness.llm.ChatRole.TOOL
                    || message.content() == null
                    || message.content().length() <= pruneChars) {
                if (pruned != null) {
                    pruned.add(message);
                }
                continue;
            }
            if (pruned == null) {
                pruned = new ArrayList<>(history.subList(0, index));
            }
            pruned.add(ChatMessage.toolResult(message.toolCallId(),
                    prunePlaceholder(message.content())));
        }
        return pruned == null ? history : List.copyOf(pruned);
    }

    /** Marker line plus kept head/tail; results the window covers stay whole. */
    private String prunePlaceholder(String content) {
        int length = content.length();
        int head = Math.min(pruneHeadChars, length);
        int tail = Math.min(pruneTailChars, Math.max(0, length - head));
        if (head + tail >= length) {
            return content; // the kept window covers everything: pruning saves nothing
        }
        return "[pruned tool result: " + length + " chars]\n"
                + content.substring(0, head)
                + "\n[...pruned " + (length - head - tail) + " chars...]\n"
                + content.substring(length - tail);
    }

    /** The estimated token pressure of the session's current derived history. */
    public int estimateTokens(String sessionId) {
        return estimateTokens(MessageDeriver.derive(sessions.events(sessionId)));
    }

    /** The configured headroom buffer in tokens. */
    public int headroomTokens() {
        return headroomTokens;
    }

    /**
     * Whether the session's derived history + headroom buffer exceeds the
     * budget (dsh pressure-model analog: the headroom reserves space for the
     * model's response so compaction triggers before the window overflows).
     * Headroom is capped at half the budget so small test budgets work.
     */
    public boolean isOverBudget(String sessionId) {
        int effectiveHeadroom = Math.min(headroomTokens, maxTokens / 2);
        return estimateTokens(sessionId) + effectiveHeadroom > maxTokens;
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
     * Region selection (dsh compaction-basic region parity): the summary
     * covers only the region up to the cut point — a tail priced at
     * {@code retainTokens} stays verbatim in the derived history, and the cut
     * never splits an assistant tool round.
     */
    public String compactNow(String sessionId) {
        List<SessionEvent> events = sessions.events(sessionId);
        List<ChatMessage> derived = MessageDeriver.derive(events);
        if (derived.isEmpty()) {
            return null;
        }
        long upToSeq = selectRegionEnd(events);
        List<ChatMessage> region = io.majo.harness.agent.loop.MessageDeriver.deriveRegion(
                events, upToSeq);
        if (region.isEmpty()) {
            // the priced tail covers everything: nothing to summarize
            return null;
        }
        List<ChatMessage> summarizeRequest = new ArrayList<>(region);
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

    /**
     * The cut point of the compactable region: walk back from the end
     * accumulating the priced tail (chars ≈ tokens·4, the same ratio as
     * {@link #estimateTokens}); then the tool-pairing guard moves the cut to
     * before an assistant tool round so a call and its results are never
     * separated. Returns the seq of the last covered event (0 = the whole
     * history is tail).
     */
    long selectRegionEnd(List<SessionEvent> events) {
        long retainChars = (long) retainTokens() * 4;
        long tailChars = 0;
        int cut = events.size();
        for (int index = events.size() - 1; index >= 0; index--) {
            tailChars += derivedChars(events.get(index));
            if (tailChars >= retainChars) {
                cut = index + 1; // events[index] is the first tail event
                break;
            }
        }
        // tool-pairing guard: the region must not end inside an assistant
        // tool round (its opening ASSISTANT_MESSAGE or any TOOL_RESULT)
        while (cut > 0 && opensToolRound(events.get(cut - 1))) {
            cut--;
        }
        return cut == 0 ? 0 : events.get(cut - 1).seq();
    }

    private static int stringLength(Object value) {
        return value == null ? 0 : String.valueOf(value).length();
    }

    /** Whether the event belongs to the log block of an assistant tool round. */
    private static boolean opensToolRound(SessionEvent event) {
        if (event.type() == SessionEventType.TOOL_RESULT) {
            return true;
        }
        Object calls = event.fields().get(io.majo.harness.session.SessionEvent.FIELD_TOOL_CALLS);
        return event.type() == SessionEventType.ASSISTANT_MESSAGE
                && calls instanceof List<?> list && !list.isEmpty();
    }

    /** The event's derived model-visible char cost (the token-estimate proxy). */
    private static long derivedChars(SessionEvent event) {
        long chars = 0;
        switch (event.type()) {
            case USER_MESSAGE, CONTEXT_NOTE, TOOL_RESULT -> chars = event.content() == null
                    ? 0 : event.content().length();
            case ASSISTANT_MESSAGE -> {
                chars = event.content() == null ? 0 : event.content().length();
                Object calls = event.fields().get(io.majo.harness.session.SessionEvent.FIELD_TOOL_CALLS);
                if (calls instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> call) {
                            chars += stringLength(call.get(
                                    io.majo.harness.session.SessionEvent.FIELD_ARGUMENTS));
                        }
                    }
                }
            }
            default -> chars = 0;
        }
        return chars;
    }
}
