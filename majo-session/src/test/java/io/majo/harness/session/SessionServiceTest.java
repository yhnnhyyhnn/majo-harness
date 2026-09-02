package io.majo.harness.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.util.Disposable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionServiceTest {

    @Test
    void appendsAreSequencedAndBroadcast() {
        Context root = Context.create();
        root.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();

        SessionService sessions = root.get(SessionService.NAME);
        List<SessionEvent> broadcast = new ArrayList<>();
        Disposable listener = root.on(SessionService.EVENT, (thisArg, args) -> {
            broadcast.add((SessionEvent) args[0]);
            return null;
        });

        String sessionId = sessions.createSession();
        sessions.append(sessionId, SessionEventType.TURN_START, Map.of());
        sessions.append(sessionId, SessionEventType.USER_MESSAGE, Map.of(SessionEvent.FIELD_CONTENT, "1+2"));

        assertThat(sessions.events(sessionId)).extracting(SessionEvent::type)
                .containsExactly(SessionEventType.TURN_START, SessionEventType.USER_MESSAGE);
        assertThat(sessions.events(sessionId)).extracting(SessionEvent::seq)
                .containsExactly(1L, 2L);
        assertThat(broadcast).extracting(SessionEvent::seq).containsExactly(1L, 2L);

        listener.dispose();
        root.fiber().disposeAsync().join();
    }

    @Test
    void unknownSessionsAndDuplicatesFailLoud() {
        Context root = Context.create();
        root.plugin(new SessionPlugin(), null).await().join();
        SessionService sessions = root.get(SessionService.NAME);

        String sessionId = sessions.createSession();
        InMemorySessionStore store = new InMemorySessionStore();
        store.createSession(sessionId);
        assertThatThrownBy(() -> store.createSession(sessionId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sessions.append("nope", SessionEventType.TURN_START, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        root.fiber().disposeAsync().join();
    }

    @Test
    void fileStoreSurvivesRecreation(@TempDir Path directory) {
        String sessionId;
        {
            Context root = Context.create();
            root.plugin(new SessionPlugin(),
                    Map.of("store", "file", "path", directory.toString())).await().join();
            SessionService sessions = root.get(SessionService.NAME);
            sessionId = sessions.createSession();
            sessions.append(sessionId, SessionEventType.TURN_START, Map.of());
            sessions.append(sessionId, SessionEventType.USER_MESSAGE,
                    Map.of(SessionEvent.FIELD_CONTENT, "persisted"));
            root.fiber().disposeAsync().join();
        }
        {
            Context root = Context.create();
            root.plugin(new SessionPlugin(),
                    Map.of("store", "file", "path", directory.toString())).await().join();
            SessionService sessions = root.get(SessionService.NAME);
            assertThat(sessions.sessionIds()).containsExactly(sessionId);
            assertThat(sessions.events(sessionId)).extracting(SessionEvent::type)
                    .containsExactly(SessionEventType.TURN_START, SessionEventType.USER_MESSAGE);
            assertThat(sessions.events(sessionId).get(1).content()).isEqualTo("persisted");
            root.fiber().disposeAsync().join();
        }
    }

    @Test
    void fileStoreDefaultsToUserHome(@TempDir Path home) {
        String previous = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            Context root = Context.create();
            root.plugin(new SessionPlugin(), Map.of("store", "file")).await().join();
            SessionService sessions = root.get(SessionService.NAME);
            String sessionId = sessions.createSession();
            sessions.append(sessionId, SessionEventType.TURN_START, Map.of());
            assertThat(Files.exists(home.resolve(".majo/sessions").resolve(sessionId + ".jsonl"))).isTrue();
            root.fiber().disposeAsync().join();
        } finally {
            System.setProperty("user.home", previous);
        }
    }
}
