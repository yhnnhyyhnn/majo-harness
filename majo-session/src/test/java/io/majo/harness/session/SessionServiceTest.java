package io.majo.harness.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.util.Disposable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
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
        root.plugin(new SessionProjectionsPlugin(), null).await().join();

        SessionService sessions = root.get(SessionService.NAME);
        List<SessionEvent> broadcast = new ArrayList<>();
        List<String> broadcastSessions = new ArrayList<>();
        Disposable listener = root.on(SessionService.EVENT, (thisArg, args) -> {
            broadcastSessions.add((String) args[0]);
            broadcast.add((SessionEvent) args[1]);
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
        assertThat(broadcastSessions).containsExactly(sessionId, sessionId);

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
    void removeDeletesFromMemoryAndFileStores() throws IOException {
        // memory store: list shrinks and events become unknown
        InMemorySessionStore memory = new InMemorySessionStore();
        String memId = memory.createSession("a");
        memory.createSession("b");
        memory.remove(memId);
        assertThat(memory.sessionIds()).containsExactly("b");
        assertThatThrownBy(() -> memory.events(memId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> memory.remove(memId))
                .isInstanceOf(IllegalArgumentException.class);

        // file store: the durable file is deleted and ids stop listing it
        Path directory = Files.createTempDirectory("majo-store-test");
        try {
            FileSessionStore file = new FileSessionStore(directory);
            String fileId = file.createSession("x");
            file.createSession("y");
            file.append(fileId, new SessionEvent(1, SessionEventType.TURN_START,
                    System.currentTimeMillis(), Map.of()));
            assertThat(Files.exists(directory.resolve("x.jsonl"))).isTrue();
            file.remove(fileId);
            assertThat(Files.exists(directory.resolve("x.jsonl"))).isFalse();
            assertThat(file.sessionIds()).containsExactly("y");
            assertThatThrownBy(() -> file.remove(fileId))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            // best-effort cleanup
            try (var stream = Files.list(directory)) {
                stream.forEach(path -> path.toFile().delete());
            }
            directory.toFile().delete();
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
            assertThat(Files.exists(home.resolve(".majo-harness/sessions").resolve(sessionId + ".jsonl"))).isTrue();
            root.fiber().disposeAsync().join();
        } finally {
            System.setProperty("user.home", previous);
        }
    }
}
