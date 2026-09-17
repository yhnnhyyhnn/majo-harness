package io.majo.harness.web.handler;

import com.sun.net.httpserver.HttpExchange;
import io.majo.harness.llm.LLMService;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionService;
import io.majo.harness.settings.SettingsService;
import io.majo.harness.web.Http;
import io.majo.harness.web.WebApiModels;
import io.majo.harness.web.WebContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Session lifecycle: list/create/delete, rename, archive, per-session model, feedback, import/export, event cursors. */
public final class SessionHandlers {

    private final WebContext ctx;

    public SessionHandlers(WebContext ctx) {
        this.ctx = ctx;
    }

    public WebApiModels.SessionsIndex sessionsIndex(Map<String, String> query) {
        SessionService sessions = ctx.boot.service(SessionService.NAME);
        String view = query.getOrDefault("view", "active"); // active | archived | all
        List<WebApiModels.SessionInfo> list = new ArrayList<>();
        for (String sessionId : sessions.sessionIds()) {
            boolean archived = SessionSupport.isArchived(ctx, sessionId);
            if ("active".equals(view) && archived) {
                continue;
            }
            if ("archived".equals(view) && !archived) {
                continue;
            }
            list.add(new WebApiModels.SessionInfo(
                    sessionId, SessionSupport.titleFor(ctx, sessionId), sessions.events(sessionId).size()));
        }
        return new WebApiModels.SessionsIndex(list);
    }

    public WebApiModels.Ok archiveSession(String sessionId, boolean archived) {
        SessionService sessions = ctx.boot.service(SessionService.NAME);
        SessionSupport.requireKnownSession(sessions, sessionId);
        SettingsService settings = ctx.boot.ctx().get(SettingsService.NAME);
        if (settings == null) {
            throw new IllegalArgumentException("settings service unavailable — cannot archive");
        }
        if (archived) {
            settings.set(SessionSupport.ARCHIVED_PREFIX + sessionId, "1");
        } else {
            settings.unset(SessionSupport.ARCHIVED_PREFIX + sessionId);
        }
        return new WebApiModels.Ok(true);
    }

    public WebApiModels.SessionDetail sessionDetail(String sessionId) {
        SessionService sessions = ctx.boot.service(SessionService.NAME);
        SessionSupport.requireKnownSession(sessions, sessionId);
        return new WebApiModels.SessionDetail(
                sessionId, SessionSupport.titleFor(ctx, sessionId),
                SessionSupport.sessionModelFor(ctx, sessionId),
                SessionSupport.eventsJson(sessions.events(sessionId)));
    }

    public WebApiModels.FeedbackIndex feedbackIndex(String sessionId) {
        SessionService sessions = ctx.boot.service(SessionService.NAME);
        SessionSupport.requireKnownSession(sessions, sessionId);
        SettingsService settings = ctx.boot.ctx().get(SettingsService.NAME);
        List<WebApiModels.FeedbackEntry> entries = new ArrayList<>();
        if (settings != null) {
            String prefix = SessionSupport.FEEDBACK_PREFIX + sessionId + ".";
            for (Map.Entry<String, String> entry : settings.entries(prefix).entrySet()) {
                String seqText = entry.getKey().substring(prefix.length());
                try {
                    entries.add(new WebApiModels.FeedbackEntry(
                            Long.parseLong(seqText), entry.getValue()));
                } catch (NumberFormatException ignored) {
                    // tolerate stray keys; ratings are best-effort facts
                }
            }
        }
        entries.sort(java.util.Comparator.comparingLong(WebApiModels.FeedbackEntry::seq));
        return new WebApiModels.FeedbackIndex(entries);
    }

    public WebApiModels.Ok rateMessage(HttpExchange exchange, String sessionId, long seq)
            throws IOException {
        requireKnownMessage(sessionId, seq);
        Map<?, ?> request = Http.JSON.readValue(exchange.getRequestBody(), Map.class);
        Object value = request.get("value");
        String rating = value == null ? null : String.valueOf(value);
        if (!"up".equals(rating) && !"down".equals(rating)) {
            throw new IllegalArgumentException("value must be up or down");
        }
        SettingsService settings = ctx.boot.ctx().get(SettingsService.NAME);
        if (settings == null) {
            throw new IllegalArgumentException("settings service unavailable — cannot store feedback");
        }
        settings.set(SessionSupport.FEEDBACK_PREFIX + sessionId + "." + seq, rating);
        return new WebApiModels.Ok(true);
    }

    public WebApiModels.Ok clearFeedback(String sessionId, long seq) {
        requireKnownMessage(sessionId, seq);
        SettingsService settings = ctx.boot.ctx().get(SettingsService.NAME);
        if (settings != null) {
            settings.unset(SessionSupport.FEEDBACK_PREFIX + sessionId + "." + seq);
        }
        return new WebApiModels.Ok(true);
    }

    private void requireKnownMessage(String sessionId, long seq) {
        SessionService sessions = ctx.boot.service(SessionService.NAME);
        SessionSupport.requireKnownSession(sessions, sessionId);
        boolean known = sessions.events(sessionId).stream().anyMatch(event -> event.seq() == seq);
        if (!known) {
            throw new IllegalArgumentException("unknown event seq " + seq + " in session " + sessionId);
        }
    }

    public WebApiModels.Ok setSessionModel(HttpExchange exchange, String sessionId)
            throws IOException {
        SessionService sessions = ctx.boot.service(SessionService.NAME);
        SessionSupport.requireKnownSession(sessions, sessionId);
        Map<?, ?> request = Http.JSON.readValue(exchange.getRequestBody(), Map.class);
        Object modelValue = request.get("model");
        if (modelValue == null || String.valueOf(modelValue).isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        String model = String.valueOf(modelValue);
        LLMService llm = ctx.boot.service(LLMService.NAME);
        if (!llm.registeredModels().contains(model)) {
            throw new IllegalArgumentException("unknown model \"" + model + "\"");
        }
        SettingsService settings = ctx.boot.ctx().get(SettingsService.NAME);
        if (settings == null) {
            throw new IllegalArgumentException("settings service unavailable — cannot persist model");
        }
        settings.set(SessionSupport.SESSION_MODEL_PREFIX + sessionId, model);
        return new WebApiModels.Ok(true);
    }

    public WebApiModels.Ok clearSessionModel(String sessionId) {
        SessionService sessions = ctx.boot.service(SessionService.NAME);
        SessionSupport.requireKnownSession(sessions, sessionId);
        SettingsService settings = ctx.boot.ctx().get(SettingsService.NAME);
        if (settings != null) {
            settings.unset(SessionSupport.SESSION_MODEL_PREFIX + sessionId);
        }
        return new WebApiModels.Ok(true);
    }

    public WebApiModels.Ok renameSession(HttpExchange exchange, String sessionId)
            throws IOException {
        SessionService sessions = ctx.boot.service(SessionService.NAME);
        SessionSupport.requireKnownSession(sessions, sessionId);
        Map<?, ?> request = Http.JSON.readValue(exchange.getRequestBody(), Map.class);
        Object titleValue = request.get("title");
        if (titleValue == null || String.valueOf(titleValue).isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        String title = String.valueOf(titleValue).trim();
        if (title.length() > 120) {
            throw new IllegalArgumentException("title too long (max 120)");
        }
        SettingsService settings = ctx.boot.ctx().get(SettingsService.NAME);
        if (settings == null) {
            throw new IllegalArgumentException("settings service unavailable — cannot persist title");
        }
        settings.set(SessionSupport.TITLE_PREFIX + sessionId, title);
        return new WebApiModels.Ok(true);
    }

    public WebApiModels.Ok deleteSession(String sessionId) {
        SessionService sessions = ctx.boot.service(SessionService.NAME);
        Object lock = ctx.lockFor(sessionId);
        synchronized (lock) {
            try {
                sessions.remove(sessionId);
                io.majo.harness.session.SessionProjections projections =
                        ctx.boot.ctx().get(io.majo.harness.session.SessionProjections.NAME);
                if (projections != null) {
                    projections.drop(sessionId);
                }
                SettingsService settings = ctx.boot.ctx().get(SettingsService.NAME);
                if (settings != null) {
                    settings.unset(SessionSupport.TITLE_PREFIX + sessionId);
                    settings.unset(SessionSupport.SESSION_MODEL_PREFIX + sessionId);
                    settings.unset(SessionSupport.ARCHIVED_PREFIX + sessionId);
                }
                return new WebApiModels.Ok(true);
            } finally {
                ctx.removeLock(sessionId);
            }
        }
    }

    /** Imports exported JSONL into a brand-new session (returns its id). */
    public WebApiModels.CreateSession importSession(HttpExchange exchange) throws IOException {
        SessionService sessions = ctx.boot.service(SessionService.NAME);
        List<SessionEvent> events = new ArrayList<>();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String[] lines = body.split("\r?\n");
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                events.add(Http.JSON.readValue(line, SessionEvent.class));
            } catch (IOException | RuntimeException e) {
                throw new IllegalArgumentException("import: invalid event on line " + (index + 1)
                        + ": " + e.getMessage());
            }
        }
        if (events.isEmpty()) {
            throw new IllegalArgumentException("import: no events in body");
        }
        String sessionId = sessions.createSession();
        try {
            sessions.importEvents(sessionId, events);
        } catch (RuntimeException e) {
            sessions.remove(sessionId); // roll back the half-imported session
            throw e;
        }
        return new WebApiModels.CreateSession(sessionId);
    }

    public WebApiModels.CreateSession createSession() {
        SessionService sessions = ctx.boot.service(SessionService.NAME);
        return new WebApiModels.CreateSession(sessions.createSession());
    }

    /** Downloads a session as replayable JSONL (raw durable events). */
    public void exportSession(HttpExchange exchange, String sessionId) throws IOException {
        SessionService sessions = ctx.boot.service(SessionService.NAME);
        if (!sessions.sessionIds().contains(sessionId)) {
            Http.json(exchange, 404, Map.of("error", "unknown session \"" + sessionId + "\""));
            return;
        }
        StringBuilder out = new StringBuilder();
        for (SessionEvent event : sessions.events(sessionId)) {
            try {
                out.append(Http.JSON.writeValueAsString(event)).append(System.lineSeparator());
            } catch (IOException e) {
                throw new IllegalStateException("cannot serialize session event", e);
            }
        }
        byte[] payload = out.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"session-" + sessionId + ".jsonl\"");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(200, payload.length);
        if (payload.length > 0) {
            exchange.getResponseBody().write(payload);
        }
        // closing the body emits the final chunk (empty exports included);
        // without it the JDK HttpClient waits on a terminator that never comes
        exchange.getResponseBody().close();
    }

    /** Events after a durable cursor (lightweight catch-up for big sessions). */
    public WebApiModels.EventsDelta eventsSince(String sessionId, Map<String, String> query) {
        SessionService sessions = ctx.boot.service(SessionService.NAME);
        SessionSupport.requireKnownSession(sessions, sessionId);
        long since = 0;
        if (query.get("since") != null && !query.get("since").isBlank()) {
            try {
                since = Long.parseLong(query.get("since"));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("since must be a non-negative number");
            }
            if (since < 0) {
                throw new IllegalArgumentException("since must be a non-negative number");
            }
        }
        List<WebApiModels.EventFrame> newer = new ArrayList<>();
        long last = since;
        for (SessionEvent event : sessions.events(sessionId)) {
            if (event.seq() > since) {
                newer.add(SessionSupport.frameOf(event));
            }
            last = Math.max(last, event.seq());
        }
        return new WebApiModels.EventsDelta(since, last, newer);
    }
}
