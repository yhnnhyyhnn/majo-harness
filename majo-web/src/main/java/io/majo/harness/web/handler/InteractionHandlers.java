package io.majo.harness.web.handler;

import com.sun.net.httpserver.HttpExchange;
import io.majo.harness.web.Http;
import io.majo.harness.web.Metrics;
import io.majo.harness.web.WebApiModels;
import io.majo.harness.web.WebContext;
import java.io.IOException;
import java.util.Map;

/** Human answers to in-flight approvals and ask-user questions. */
public final class InteractionHandlers {

    private final WebContext ctx;

    public InteractionHandlers(WebContext ctx) {
        this.ctx = ctx;
    }

    public WebApiModels.Ok decideApproval(HttpExchange exchange, String id) throws IOException {
        Map<?, ?> request = Http.JSON.readValue(exchange.getRequestBody(), Map.class);
        Object decision = request.get("decision");
        boolean granted = "allow".equalsIgnoreCase(String.valueOf(decision));
        if (!granted && !"reject".equalsIgnoreCase(String.valueOf(decision))) {
            throw new IllegalArgumentException("decision must be allow or reject");
        }
        if (!ctx.pending.decideApproval(id, granted)) {
            throw new IllegalArgumentException("unknown or expired approval " + id);
        }
        Metrics.approvalDecided();
        return new WebApiModels.Ok(true);
    }

    public WebApiModels.Ok answerQuestion(HttpExchange exchange, String id) throws IOException {
        Map<?, ?> request = Http.JSON.readValue(exchange.getRequestBody(), Map.class);
        Object answer = request.get("answer");
        if (answer == null) {
            throw new IllegalArgumentException("answer must not be null");
        }
        if (!ctx.pending.answerQuestion(id, String.valueOf(answer))) {
            throw new IllegalArgumentException("unknown or expired question " + id);
        }
        Metrics.questionAnswered();
        return new WebApiModels.Ok(true);
    }
}
