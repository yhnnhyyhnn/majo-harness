package io.majo.harness.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the session file format contract: versioned names, the header line,
 * header-only stats, and the one-step legacy migration.
 */
class SessionFileFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String eventLine(long seq, SessionEventType type, String content) throws Exception {
        return MAPPER.writeValueAsString(new SessionEvent(seq, type, 1L + seq,
                Map.of(SessionEvent.FIELD_CONTENT, content)));
    }

    @Test
    void legacyFileMigratesTransparentlyOnFirstTouch(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("old.jsonl"),
                eventLine(1, SessionEventType.USER_MESSAGE, "legacy one") + "\n"
                        + eventLine(2, SessionEventType.USER_MESSAGE, "legacy two") + "\n",
                StandardCharsets.UTF_8);

        FileSessionStore store = new FileSessionStore(directory);
        assertThat(store.sessionIds()).containsExactly("old");
        List<SessionEvent> events = store.events("old");
        assertThat(events).extracting(SessionEvent::content)
                .containsExactly("legacy one", "legacy two");

        // migrated in place: versioned name, header prepended, legacy gone
        assertThat(Files.exists(directory.resolve("old.v1.jsonl"))).isTrue();
        assertThat(Files.exists(directory.resolve("old.jsonl"))).isFalse();
        List<String> lines = Files.readAllLines(directory.resolve("old.v1.jsonl"), StandardCharsets.UTF_8);
        SessionFileFormat.Header header = SessionFileFormat.parseHeader(lines.get(0));
        assertThat(header).isNotNull();
        SessionFileFormat.validate(header, "old", directory.resolve("old.v1.jsonl"));
        assertThat(lines).hasSize(3);

        // the migrated session keeps working: append lands on the new file
        store.append("old", new SessionEvent(3, SessionEventType.USER_MESSAGE, 3L,
                Map.of(SessionEvent.FIELD_CONTENT, "post-migration")));
        assertThat(store.events("old")).hasSize(3);
    }

    @Test
    void migrationIsIdempotentWhenLegacySurvived(@TempDir Path directory) throws Exception {
        // a crashed migration left both files: the versioned one wins
        Files.writeString(directory.resolve("old.jsonl"),
                eventLine(1, SessionEventType.USER_MESSAGE, "legacy") + "\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("old.v1.jsonl"),
                SessionFileFormat.headerLine("old", 1) + "\n"
                        + eventLine(1, SessionEventType.USER_MESSAGE, "current") + "\n",
                StandardCharsets.UTF_8);

        FileSessionStore store = new FileSessionStore(directory);
        assertThat(store.events("old")).extracting(SessionEvent::content).containsExactly("current");
        SessionFileFormat.migrate(directory);
        assertThat(Files.exists(directory.resolve("old.jsonl"))).isFalse();
        assertThat(Files.exists(directory.resolve("old.v1.jsonl"))).isTrue();
    }

    @Test
    void newerGenerationFailsLoud(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("future.v2.jsonl"),
                SessionFileFormat.headerLine("future", 2) + "\n", StandardCharsets.UTF_8);
        FileSessionStore store = new FileSessionStore(directory);

        assertThat(store.sessionIds()).containsExactly("future");
        assertThatThrownBy(() -> store.events("future"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("downgrade");
        assertThatThrownBy(() -> store.append("future", new SessionEvent(1,
                SessionEventType.USER_MESSAGE, 1L, Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("downgrade");
        assertThatThrownBy(() -> SessionFileFormat.stat(directory.resolve("future.v2.jsonl")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("downgrade");
    }

    @Test
    void headerOnlyStatAndListSkipEventBodies(@TempDir Path directory) throws Exception {
        FileSessionStore store = new FileSessionStore(directory);
        store.createSession("a");
        store.append("a", new SessionEvent(1, SessionEventType.USER_MESSAGE, 1L, Map.of()));
        store.createSession("b");

        List<SessionFileFormat.Stat> stats = SessionFileFormat.listStats(directory);
        assertThat(stats).extracting(SessionFileFormat.Stat::sessionId).containsExactly("a", "b");
        assertThat(stats).extracting(SessionFileFormat.Stat::version).containsOnly(1);
        for (SessionFileFormat.Stat stat : stats) {
            Path file = directory.resolve(stat.sessionId() + ".v1.jsonl");
            assertThat(stat.sizeBytes()).isEqualTo(Files.size(file));
        }
    }

    @Test
    void eventCountIsCheapButExact(@TempDir Path directory) throws Exception {
        FileSessionStore store = new FileSessionStore(directory);
        store.createSession("c");
        assertThat(store.eventCount("c")).isZero();
        store.append("c", new SessionEvent(1, SessionEventType.USER_MESSAGE, 1L, Map.of()));
        store.append("c", new SessionEvent(2, SessionEventType.USER_MESSAGE, 2L, Map.of()));
        assertThat(store.eventCount("c")).isEqualTo(2);
        assertThat(store.events("c")).hasSize(2);
        assertThat(store.eventCount("ghost")).isZero();
    }

    @Test
    void contentFileWithoutHeaderFailsLoud(@TempDir Path directory) throws Exception {
        FileSessionStore store = new FileSessionStore(directory);
        // a v1-named file with event content but no header is not a store file
        Files.writeString(directory.resolve("raw.v1.jsonl"),
                eventLine(1, SessionEventType.USER_MESSAGE, "no header") + "\n",
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> store.events("raw"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("header");
    }

    @Test
    void migrateIsNoOpOnEmptyOrAbsentDirectory(@TempDir Path directory) throws Exception {
        SessionFileFormat.migrate(directory); // exists, nothing legacy
        SessionFileFormat.migrate(directory.resolve("missing")); // absent
        assertThat(SessionFileFormat.listStats(directory)).isEmpty();
    }
}
