package io.majo.harness.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Durable {@link SessionStore}: one JSON Lines file per session under a
 * directory, named by generation ({@link SessionFileFormat}). Each file
 * starts with a one-line format header; every following line is one
 * {@link SessionEvent}, so the file stays append-only and replayable.
 *
 * <p>Generation handling is lazy: legacy unversioned {@code <id>.jsonl}
 * files migrate in one step when the session is next touched, and files
 * from a newer generation than this build understands fail loudly. A crash
 * can leave a partial trailing line — it never was a committed event, so
 * the next read truncates it (log repair); a complete but unparseable line
 * is corruption and still fails loudly.
 *
 * <p>Locking is per session file (not global), so turns of different sessions
 * never serialize on the store — parallel turn support depends on it.
 */
public final class FileSessionStore implements SessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUFFIX = ".jsonl";
    private static final Pattern VERSIONED = Pattern.compile("^(.+)\\.v(\\d+)\\.jsonl$");

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
        return SessionFileFormat.fileOf(directory, sessionId, SessionFileFormat.CURRENT);
    }

    /**
     * Resolves the session's durable file: the current generation when
     * present; otherwise a one-step migration from the legacy name, or a
     * loud failure when only a newer generation exists. The returned path
     * may not exist (unknown session).
     */
    private Path resolve(String sessionId) {
        Path current = fileOf(sessionId);
        if (Files.exists(current)) {
            return current;
        }
        if (!Files.isDirectory(directory)) {
            return current;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                Matcher versioned = VERSIONED.matcher(path.getFileName().toString());
                if (versioned.matches() && versioned.group(1).equals(sessionId)
                        && Integer.parseInt(versioned.group(2)) > SessionFileFormat.CURRENT) {
                    throw new IllegalStateException("session \"" + sessionId
                            + "\" was written by generation v" + versioned.group(2)
                            + " but this build understands v" + SessionFileFormat.CURRENT
                            + " (downgrade is not supported)");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot resolve session file for \"" + sessionId + "\"", e);
        }
        try {
            SessionFileFormat.migrateSession(directory, sessionId);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot migrate session \"" + sessionId + "\" to generation v"
                            + SessionFileFormat.CURRENT, e);
        }
        return current;
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
                if (Files.exists(fileOf(sessionId))
                        || Files.exists(SessionFileFormat.legacyFileOf(directory, sessionId))) {
                    throw new IllegalArgumentException("session \"" + sessionId + "\" already exists");
                }
                Files.writeString(fileOf(sessionId),
                        SessionFileFormat.headerLine(sessionId, SessionFileFormat.CURRENT) + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
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
                Path file = resolve(sessionId);
                if (!Files.exists(file)) {
                    throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
                }
                Files.writeString(file, MAPPER.writeValueAsString(event) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException("cannot append to session \"" + sessionId + "\"", e);
            }
        }
    }

    @Override
    public List<SessionEvent> events(String sessionId) {
        synchronized (lockFor(sessionId)) {
            Path file = resolve(sessionId);
            if (!Files.exists(file)) {
                return List.of();
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read session \"" + sessionId + "\"", e);
            }
            int firstEvent = 0;
            if (!lines.isEmpty() && !lines.get(0).isBlank()) {
                SessionFileFormat.Header header = SessionFileFormat.parseHeader(lines.get(0));
                if (header == null) {
                    throw new IllegalStateException("session \"" + sessionId
                            + "\" file is missing its format header (not a v"
                            + SessionFileFormat.CURRENT + " log?)");
                }
                SessionFileFormat.validate(header, sessionId, file);
                firstEvent = 1;
            }
            List<SessionEvent> events = new ArrayList<>();
            // exclusive end of the parseable prefix; everything after a
            // partial trailing line is uncommitted and gets truncated away
            int committedEnd = firstEvent;
            for (int index = firstEvent; index < lines.size(); index++) {
                String line = lines.get(index).trim();
                if (line.isEmpty()) {
                    committedEnd = index + 1;
                    continue;
                }
                try {
                    events.add(MAPPER.readValue(line, SessionEvent.class));
                    committedEnd = index + 1;
                } catch (IOException parse) {
                    boolean truncatedTail = false;
                    if (index == lines.size() - 1) {
                        try {
                            truncatedTail = endsWithoutNewline(file);
                        } catch (IOException io) {
                            throw new IllegalStateException(
                                    "cannot inspect the tail of session \"" + sessionId + "\"", io);
                        }
                    }
                    if (truncatedTail) {
                        // a crash can leave a partial trailing line (no newline):
                        // it never was a committed event, so repair the file
                        repairTruncate(file, lines, committedEnd);
                        break;
                    }
                    throw new IllegalStateException(
                            "cannot parse line " + (index + 1) + " of session \""
                                    + sessionId + "\": " + parse.getMessage(), parse);
                }
            }
            events.sort(Comparator.comparingLong(SessionEvent::seq));
            return List.copyOf(events);
        }
    }

    /**
     * Cheap event count: non-blank lines minus the header, with no JSON
     * parsing — valid because reads repair partial trailing lines, so a
     * committed file is exactly header + one line per event. Equals
     * {@code events(sessionId).size()} for existing sessions.
     */
    @Override
    public int eventCount(String sessionId) {
        synchronized (lockFor(sessionId)) {
            try {
                Path file = resolve(sessionId);
                if (!Files.exists(file)) {
                    return 0;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                int count = 0;
                for (int index = 0; index < lines.size(); index++) {
                    if (lines.get(index).isBlank()) {
                        continue;
                    }
                    if (index == 0 && SessionFileFormat.parseHeader(lines.get(0)) != null) {
                        continue;
                    }
                    count++;
                }
                return count;
            } catch (IOException e) {
                throw new IllegalStateException("cannot count session \"" + sessionId + "\"", e);
            }
        }
    }

    private static boolean endsWithoutNewline(Path file) throws IOException {
        long size = Files.size(file);
        if (size == 0) {
            return false;
        }
        try (RandomAccessFile random = new RandomAccessFile(file.toFile(), "r")) {
            random.seek(size - 1);
            return random.read() != '\n';
        }
    }

    /** Rewrites the file to its parseable prefix, dropping the partial tail. */
    private static void repairTruncate(Path file, List<String> lines, int committedEnd) {
        StringBuilder prefix = new StringBuilder();
        for (int index = 0; index < committedEnd; index++) {
            prefix.append(lines.get(index)).append('\n');
        }
        try {
            Files.writeString(file, prefix.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("cannot repair partial trailing line of " + file, e);
        }
    }

    @Override
    public List<String> sessionIds() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.map(path -> idOf(path.getFileName().toString()))
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list session store directory " + directory, e);
        }
    }

    /** Session id encoded in a store file name, or {@code null} for non-store files. */
    private static String idOf(String name) {
        Matcher versioned = VERSIONED.matcher(name);
        if (versioned.matches()) {
            return versioned.group(1);
        }
        if (name.endsWith(SUFFIX)) {
            return name.substring(0, name.length() - SUFFIX.length());
        }
        return null;
    }

    @Override
    public void remove(String sessionId) {
        synchronized (lockFor(sessionId)) {
            boolean removed = false;
            try {
                if (Files.isDirectory(directory)) {
                    try (Stream<Path> paths = Files.list(directory)) {
                        for (Path path : paths.toList()) {
                            if (sessionId.equals(idOf(path.getFileName().toString()))) {
                                Files.deleteIfExists(path);
                                removed = true;
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot remove session \"" + sessionId + "\"", e);
            }
            if (!removed) {
                throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
            }
            fileLocks.remove(sessionId);
        }
    }
}
