package io.majo.harness.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Request-context injection: instructions + time land once per session as a
 * durable CONTEXT_NOTE, restart-safe via the log marker, and absent when
 * there is nothing to say.
 */
class ContextPluginTest {

    private static List<SessionEventType> types(String sessionId, SessionService sessions) {
        return sessions.events(sessionId).stream().map(SessionEvent::type).toList();
    }

    @Test
    void injectsInstructionsAndTimeOncePerSession(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("AGENTS.md"), "Always answer in JSON.");
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new ContextPlugin(),
                Map.of("dir", dir.toString(), "timeContext", true)).await().join();
        SessionService sessions = ctx.get(SessionService.NAME);

        String sessionId = sessions.createSession();
        sessions.append(sessionId, SessionEventType.TURN_START, Map.of());
        sessions.append(sessionId, SessionEventType.USER_MESSAGE,
                Map.of(SessionEvent.FIELD_CONTENT, "hi"));

        // the note lands between turn start and the user message
        assertThat(types(sessionId, sessions)).containsExactly(
                SessionEventType.TURN_START, SessionEventType.CONTEXT_NOTE,
                SessionEventType.USER_MESSAGE);
        SessionEvent note = sessions.events(sessionId).stream()
                .filter(event -> event.type() == SessionEventType.CONTEXT_NOTE)
                .findFirst().orElseThrow();
        assertThat(note.content()).startsWith(ContextPlugin.MARKER)
                .contains("workspace instructions:", "Always answer in JSON.", "current time: ");

        // a second turn does not re-inject
        sessions.append(sessionId, SessionEventType.TURN_START, Map.of());
        assertThat(types(sessionId, sessions).stream()
                .filter(type -> type == SessionEventType.CONTEXT_NOTE).count()).isEqualTo(1);
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void restartDoesNotReinjectSessionsThatAlreadyCarryTheNote(@TempDir Path dir)
            throws Exception {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new ContextPlugin(), Map.of("dir", dir.toString())).await().join();
        SessionService sessions = ctx.get(SessionService.NAME);

        // a session from a previous process already carries the note
        String sessionId = sessions.createSession();
        sessions.append(sessionId, SessionEventType.CONTEXT_NOTE,
                Map.of(SessionEvent.FIELD_CONTENT,
                        ContextPlugin.MARKER + "workspace instructions:\nold"));
        sessions.append(sessionId, SessionEventType.TURN_START, Map.of());

        assertThat(types(sessionId, sessions).stream()
                .filter(type -> type == SessionEventType.CONTEXT_NOTE).count()).isEqualTo(1);
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void nothingToSayMeansNoInjection(@TempDir Path dir) {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new ContextPlugin(),
                Map.of("dir", dir.toString(), "timeContext", false)).await().join();
        SessionService sessions = ctx.get(SessionService.NAME);

        String sessionId = sessions.createSession();
        sessions.append(sessionId, SessionEventType.TURN_START, Map.of());
        sessions.append(sessionId, SessionEventType.USER_MESSAGE,
                Map.of(SessionEvent.FIELD_CONTENT, "hi"));

        assertThat(types(sessionId, sessions)).doesNotContain(SessionEventType.CONTEXT_NOTE);
        ctx.fiber().disposeAsync().join();
    }
}
