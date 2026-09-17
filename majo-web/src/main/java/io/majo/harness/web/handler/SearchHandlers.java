package io.majo.harness.web.handler;

import io.majo.harness.session.SessionService;
import io.majo.harness.web.WebApiModels;
import io.majo.harness.web.WebContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Full-text search across durable session events (title + message text). */
public final class SearchHandlers {

    private final WebContext ctx;

    public SearchHandlers(WebContext ctx) {
        this.ctx = ctx;
    }

    public WebApiModels.SearchIndex search(String queryText) {
        if (queryText.isEmpty()) {
            return new WebApiModels.SearchIndex(List.of());
        }
        SessionService sessions = ctx.boot.service(SessionService.NAME);
        String needle = queryText.toLowerCase(Locale.ROOT);
        List<WebApiModels.SearchHit> hits = new ArrayList<>();
        for (String sessionId : sessions.sessionIds()) {
            if (hits.size() >= 25) {
                break;
            }
            String title = SessionSupport.titleFor(ctx, sessionId);
            Boolean archived = SessionSupport.isArchived(ctx, sessionId);
            if (title.toLowerCase(Locale.ROOT).contains(needle)) {
                hits.add(new WebApiModels.SearchHit(sessionId, title, "title match", null, archived));
                continue;
            }
            String snippet = null;
            Long seq = null;
            for (SearchIndex.Entry entry : ctx.searchIndex.entries(sessions, sessionId)) {
                if (entry.lower().contains(needle)) {
                    seq = entry.seq();
                    snippet = snippetAround(entry.display(), needle);
                    break;
                }
            }
            if (snippet != null) {
                hits.add(new WebApiModels.SearchHit(sessionId, title, snippet, seq, archived));
            }
        }
        return new WebApiModels.SearchIndex(hits);
    }

    /** Whitespace-collapsed excerpt centered on the match (≤160 chars). */
    private static String snippetAround(String display, String needle) {
        String snippet = display.replaceAll("\\s+", " ").trim();
        int at = snippet.toLowerCase(Locale.ROOT).indexOf(needle);
        if (snippet.length() > 160) {
            int start = Math.max(0, at - 60);
            snippet = (start > 0 ? "…" : "") + snippet.substring(start)
                    .substring(0, Math.min(160, snippet.length() - start)) + "…";
        }
        return snippet;
    }
}
