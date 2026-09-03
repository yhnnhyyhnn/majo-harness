package io.majo.harness.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Durable {@link SessionStore}: one JSON Lines file per session under a
 * directory. Each line is one {@link SessionEvent}, so the file stays
 * append-only and replayable.
 */
public final class FileSessionStore implements SessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUFFIX = ".jsonl";

    private final Path directory;

    public FileSessionStore(Path directory) {
        this.directory = directory;
    }

    private Path fileOf(String sessionId) {
        return directory.resolve(sessionId + SUFFIX);
    }

    @Override
    public synchronized String createSession(String sessionId) {
        try {
            Files.createDirectories(directory);
            if (Files.exists(fileOf(sessionId))) {
                throw new IllegalArgumentException("session \"" + sessionId + "\" already exists");
            }
            Files.createFile(fileOf(sessionId));
            return sessionId;
        } catch (IOException e) {
            throw new IllegalStateException("cannot create session store file for \"" + sessionId + "\"", e);
        }
    }

    @Override
    public synchronized void append(String sessionId, SessionEvent event) {
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

    @Override
    public synchronized List<SessionEvent> events(String sessionId) {
        Path file = fileOf(sessionId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            List<SessionEvent> events = new ArrayList<>();
            for (String line : lines.filter(l -> !l.isBlank()).toList()) {
                events.add(MAPPER.readValue(line, SessionEvent.class));
            }
            events.sort(Comparator.comparingLong(SessionEvent::seq));
            return List.copyOf(events);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read session \"" + sessionId + "\"", e);
        }
    }

    @Override
    public synchronized List<String> sessionIds() {
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
    public synchronized void remove(String sessionId) {
        try {
            if (!Files.deleteIfExists(fileOf(sessionId))) {
                throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot remove session \"" + sessionId + "\"", e);
        }
    }
}
