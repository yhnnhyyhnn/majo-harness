package io.majo.harness.web.handler;

import io.majo.harness.session.SessionService;
import io.majo.harness.web.WebApiModels;
import io.majo.harness.web.WebContext;
import java.util.ArrayList;
import java.util.List;

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
        String needle = queryText.toLowerCase();
        List<WebApiModels.SearchHit> hits = new ArrayList<>();
        for (String sessionId : sessions.sessionIds()) {
            if (hits.size() >= 25) {
                break;
            }
            String title = SessionSupport.titleFor(ctx, sessionId);
            Boolean archived = SessionSupport.isArchived(ctx, sessionId);
            if (title.toLowerCase().contains(needle)) {
                hits.add(new WebApiModels.SearchHit(sessionId, title, "title match", null, archived));
                continue;
            }
            String snippet = null;
            Long seq = null;
            for (var event : sessions.events(sessionId)) {
                String content = event.content();
                if (content != null && content.toLowerCase().contains(needle)) {
                    seq = event.seq();
                    snippet = content.replaceAll("\\s+", " ").trim();
                    int at = snippet.toLowerCase().indexOf(needle);
                    if (snippet.length() > 160) {
                        int start = Math.max(0, at - 60);
                        snippet = (start > 0 ? "…" : "") + snippet.substring(start)
                                .substring(0, Math.min(160, snippet.length() - start)) + "…";
                    }
                    break;
                }
            }
            if (snippet != null) {
                hits.add(new WebApiModels.SearchHit(sessionId, title, snippet, seq, archived));
            }
        }
        return new WebApiModels.SearchIndex(hits);
    }
}
