package io.majo.harness.compaction;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.agent.loop.AgentLoopEvents;
import io.majo.harness.agent.loop.MessageDeriver;
import io.majo.harness.llm.LLMService;
import io.majo.harness.session.SessionService;
import java.util.HashMap;
import java.util.Map;

/**
 * Mounts context management (dsh compaction): the compaction runtime plus a
 * listener on the loop's before-request waterfall. When the derived history
 * is over budget, the listener persists a summary (durable CONTEXT_COMPACTION
 * event) and returns the fresh derivation — the request the model finally
 * sees is exactly what the log rebuilds.
 *
 * <p>Config: {@code {maxTokens: <n>, pruneChars: <n>, headChars: <n>,
 * tailChars: <n>}}} — estimated-token budget (default 32000) and the
 * tool-result pruning knobs applied to derived history older than the final
 * assistant round: threshold (default 8192, kept head 4096 / tail 1024).
 */
public final class CompactionPlugin implements Plugin {

    public static final String NAME = "compaction";

    @Override
    public Object apply(Context ctx, Object config) {
        SessionService sessions = ctx.get(SessionService.NAME);
        LLMService llm = ctx.get(LLMService.NAME);
        CompactionService compaction = new CompactionService(ctx, sessions, llm, config);
        return ctx.on(AgentLoopEvents.BEFORE_REQUEST, (thisArg, args) -> {
            String sessionId = (String) args[0];
            compaction.maybeCompact(sessionId);
            // the summary (if any) is durable now: return the fresh derivation,
            // with oversized older tool results collapsed to placeholders
            return compaction.pruneToolResults(MessageDeriver.derive(sessions.events(sessionId)));
        });
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(SessionService.NAME, null);
        inject.put(LLMService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
