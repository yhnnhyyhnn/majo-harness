package io.majo.harness.web.handler;

import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import io.majo.harness.settings.SettingsService;
import io.majo.harness.title.SessionTitleService;
import io.majo.harness.web.Http;
import io.majo.harness.web.WebApiModels;
import io.majo.harness.web.WebContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Session metadata helpers shared by several handler groups. */
final class SessionSupport {

    static final String TITLE_PREFIX = "session.title.";
    static final String SESSION_MODEL_PREFIX = "session.model.";
    static final String ARCHIVED_PREFIX = "session.archived.";
    static final String FEEDBACK_PREFIX = "feedback.";

    private SessionSupport() {
    }

    /** Fails loudly when the session id does not exist (HTTP 400). */
    static void requireKnownSession(SessionService sessions, String sessionId) {
        if (!sessions.sessionIds().contains(sessionId)) {
            throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
        }
    }

    static boolean isArchived(WebContext ctx, String sessionId) {
        SettingsService settings = ctx.boot.ctx().get(SettingsService.NAME);
        return settings != null && "1".equals(settings.get(ARCHIVED_PREFIX + sessionId));
    }

    /** The per-session model override (settings), or {@code null}. */
    static String sessionModelFor(WebContext ctx, String sessionId) {
        SettingsService settings = ctx.boot.ctx().get(SettingsService.NAME);
        if (settings == null) {
            return null;
        }
        String override = settings.get(SESSION_MODEL_PREFIX + sessionId);
        return override == null || override.isBlank() ? null : override;
    }

    /** User rename wins; otherwise the derived heuristic title. */
    static String titleFor(WebContext ctx, String sessionId) {
        SettingsService settings = ctx.boot.ctx().get(SettingsService.NAME);
        String override = settings == null ? null : settings.get(TITLE_PREFIX + sessionId);
        if (override != null && !override.isBlank()) {
            return override;
        }
        SessionTitleService titles = ctx.boot.service(SessionTitleService.NAME);
        return titles.title(sessionId);
    }

    static List<WebApiModels.EventFrame> eventsJson(List<SessionEvent> events) {
        List<WebApiModels.EventFrame> result = new ArrayList<>();
        for (SessionEvent event : events) {
            result.add(frameOf(event));
        }
        return result;
    }

    static WebApiModels.EventFrame frameOf(SessionEvent event) {
        Map<String, Object> fields = event.fields();
        List<WebApiModels.ToolCallFrame> toolCalls = null;
        if (event.type() == SessionEventType.ASSISTANT_MESSAGE
                && fields.containsKey(SessionEvent.FIELD_TOOL_CALLS)
                && fields.get(SessionEvent.FIELD_TOOL_CALLS) instanceof List<?> calls) {
            toolCalls = new ArrayList<>();
            for (Object item : calls) {
                if (item instanceof Map<?, ?> call) {
                    toolCalls.add(new WebApiModels.ToolCallFrame(
                            Http.stringField(call, SessionEvent.FIELD_TOOL_NAME),
                            Http.stringField(call, SessionEvent.FIELD_ARGUMENTS),
                            Http.stringField(call, SessionEvent.FIELD_TOOL_CALL_ID)));
                }
            }
        }
        List<String> toolNames = null;
        if (event.type() == SessionEventType.REQUEST_HEADER
                && fields.get(SessionEvent.FIELD_TOOL_NAMES) instanceof List<?> raw) {
            toolNames = raw.stream().map(String::valueOf).toList();
        }
        return new WebApiModels.EventFrame(
                event.seq(),
                event.type().name(),
                event.content(),
                toolCalls,
                event.type() == SessionEventType.TOOL_RESULT
                        ? Http.stringField(fields, SessionEvent.FIELD_TOOL_NAME) : null,
                event.type() == SessionEventType.TOOL_RESULT
                        ? fields.get(SessionEvent.FIELD_OK) instanceof Boolean ok ? ok : null : null,
                event.type() == SessionEventType.REQUEST_HEADER
                        ? Http.stringField(fields, SessionEvent.FIELD_MODEL) : null,
                toolNames,
                event.type() == SessionEventType.TOOL_RESULT
                        && fields.get(SessionEvent.FIELD_DATA) instanceof Map<?, ?> data
                                ? (Map<String, Object>) (Map<?, ?>) data : null,
                event.timestamp());
    }
}
