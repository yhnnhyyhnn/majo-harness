package io.majo.harness.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Durable {@link SessionStore}: one JSON Lines file per session under a
 * directory. Each line is one {@link SessionEvent}, so the file stays
 * append-only and replayable.
 *
 * <p>Locking is per session file (not global), so turns of different sessions
 * never serialize on the store — parallel turn support depends on it.
 */
public final class FileSessionStore implements SessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUFFIX = ".jsonl";

    private final Path directory;
    private final Object creationLock = new Object();
    /** One monitor per session file so different sessions never serialize. */
    private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();

    public FileSessionStore(Path directory) {
        this.directory = directory;
    }

    private Object lockFor(String sessionId) {
        return fileLocks.computeIfAbsent(sessionId, ignored -> new Object());
    }

    private Path fileOf(String sessionId) {
        return directory.resolve(sessionId + SUFFIX);
    }

    @Override
    public String createSession(String sessionId) {
        synchronized (creationLock) {
            try {
                Files.createDirectories(directory);
            } catch (IOException e) {
                throw new IllegalStateException("cannot create session directory", e);
            }
        }
        synchronized (lockFor(sessionId)) {
            try {
                if (Files.exists(fileOf(sessionId))) {
                    throw new IllegalArgumentException("session \"" + sessionId + "\" already exists");
                }
                Files.createFile(fileOf(sessionId));
                return sessionId;
            } catch (IOException e) {
                throw new IllegalStateException(
                        "cannot create session store file for \"" + sessionId + "\"", e);
            }
        }
    }

    @Override
    public void append(String sessionId, SessionEvent event) {
        synchronized (lockFor(sessionId)) {
            try {
                Path file = fileOf(sessionId);
                if (!Files.exists(file)) {
                    throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
                }
                Files.writeString(file, MAPPER.writeValueAsString(event) + System.lineSeparator(),
                        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException("cannot append to session \"" + sessionId + "\"", e);
            }
        }
    }

    @Override
    public List<SessionEvent> events(String sessionId) {
        synchronized (lockFor(sessionId)) {
            Path file = fileOf(sessionId);
            if (!Files.exists(file)) {
                return List.of();
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read session \"" + sessionId + "\"", e);
            }
            if (lines.isEmpty()) {
                return List.of();
            }
            return parseLines(lines, sessionId);
        }
    }

    private static List<SessionEvent> parseLines(List<String> lines, String sessionId) {
        List<SessionEvent> events = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                events.add(MAPPER.readValue(line, SessionEvent.class));
            } catch (IOException e) {
                if (index == lines.size() - 1) {
                    // a crash can leave a partial trailing line (no newline);
                    // drop it instead of failing the whole session
                    break;
                }
                throw new IllegalStateException(
                        "cannot parse line " + (index + 1) + " of session \"" + sessionId + "\": "
                                + e.getMessage(), e);
            }
        }
        events.sort(Comparator.comparingLong(SessionEvent::seq));
        return List.copyOf(events);
    }

    @Override
    public List<String> sessionIds() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(SUFFIX))
                    .map(path -> {
                        String name = path.getFileName().toString();
                        return name.substring(0, name.length() - SUFFIX.length());
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list session store directory " + directory, e);
        }
    }

    @Override
    public void remove(String sessionId) {
        synchronized (lockFor(sessionId)) {
            try {
                if (!Files.deleteIfExists(fileOf(sessionId))) {
                    throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot remove session \"" + sessionId + "\"", e);
            }
            fileLocks.remove(sessionId);
        }
    }
}
