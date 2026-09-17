package io.majo.harness.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Session log file format: name generations, the header line, header-only
 * stats, and migration.
 *
 * <p>Durable files are named {@code <sessionId>.v<generation>.jsonl}; the
 * first line of every file is a format header
 * ({"majo-session-file":true,"version":N,"sessionId":"…"}), every following
 * line is one {@link SessionEvent}. The generation in the name lets future
 * format changes migrate explicitly instead of being guessed from content:
 * legacy unversioned {@code <id>.jsonl} files move to the current generation
 * in one step (header prepended, event bytes verbatim), and files from a
 * newer generation than this build understands fail loudly (downgrade
 * protection).
 */
public final class SessionFileFormat {

    /** File generation this build reads and writes. */
    public static final int CURRENT = 1;

    private static final String SUFFIX = ".jsonl";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionFileFormat() {
    }

    /** Durable file of {@code sessionId} at {@code generation}. */
    public static Path fileOf(Path directory, String sessionId, int generation) {
        return directory.resolve(sessionId + ".v" + generation + SUFFIX);
    }

    /** Legacy (pre-generation) file of {@code sessionId}. */
    public static Path legacyFileOf(Path directory, String sessionId) {
        return directory.resolve(sessionId + SUFFIX);
    }

    /** The format header line of a session file at {@code generation}. */
    public static String headerLine(String sessionId, int generation) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "majo-session-file", true,
                    "version", generation,
                    "sessionId", sessionId));
        } catch (IOException e) {
            throw new IllegalStateException("cannot render session file header", e);
        }
    }

    /** Parses a line into a format header, or {@code null} when it is not one. */
    public static Header parseHeader(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            if (node.isObject() && node.has("version") && node.has("sessionId")
                    && node.get("version").isInt()) {
                return new Header(node.get("version").asInt(), node.get("sessionId").asText());
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Validated header for {@code expectedSessionId}: fails loudly on downgrade or id drift. */
    public static void validate(Header header, String expectedSessionId, Path file) {
        if (header.version() > CURRENT) {
            throw new IllegalStateException("session file " + file + " was written by generation v"
                    + header.version() + " but this build understands v" + CURRENT
                    + " (downgrade is not supported)");
        }
        if (!header.sessionId().equals(expectedSessionId)) {
            throw new IllegalStateException("session file " + file + " declares sessionId \""
                    + header.sessionId() + "\" but was opened as \"" + expectedSessionId + "\"");
        }
    }

    /** Header-only metadata of one session file: first line only, bodies untouched. */
    public static Stat stat(Path file) {
        try {
            Header header = firstHeader(file);
            if (header == null) {
                throw new IllegalStateException("session file " + file + " is missing its format header");
            }
            if (header.version() > CURRENT) {
                throw new IllegalStateException("session file " + file + " was written by generation v"
                        + header.version() + " but this build understands v" + CURRENT
                        + " (downgrade is not supported)");
            }
            return new Stat(header.sessionId(), header.version(), Files.size(file));
        } catch (IOException e) {
            throw new IllegalStateException("cannot stat session file " + file, e);
        }
    }

    /**
     * Header-only listing of every versioned session file in the directory —
     * directory stats never parse event bodies.
     */
    public static List<Stat> listStats(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Stat> stats = new ArrayList<>();
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                if (path.getFileName().toString().endsWith(".v" + CURRENT + SUFFIX)) {
                    stats.add(stat(path));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot list session store directory " + directory, e);
        }
        stats.sort((a, b) -> a.sessionId().compareTo(b.sessionId()));
        return List.copyOf(stats);
    }

    /**
     * Migrates every session file in the directory to {@link #CURRENT}.
     * Idempotent: files already at the current generation are untouched, and
     * a legacy file whose target exists (a migration that lost its last
     * delete) just cleans up. Future generations plug additional steps in
     * ahead of the legacy fallback, keeping migration one explicit chain.
     */
    public static void migrate(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<String> legacyIds = new ArrayList<>();
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(SUFFIX) && !name.contains(".v")) {
                    legacyIds.add(name.substring(0, name.length() - SUFFIX.length()));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot scan session store directory " + directory, e);
        }
        try {
            for (String id : legacyIds) {
                migrateSession(directory, id);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot migrate session store directory " + directory, e);
        }
    }

    /**
     * One migration step for {@code sessionId}: the legacy unversioned file
     * becomes a current-generation file (header prepended, event bytes
     * verbatim) via a temp file and an atomic move, then the legacy file is
     * removed. Missing legacy files are a no-op.
     */
    public static void migrateSession(Path directory, String sessionId) throws IOException {
        Path legacy = legacyFileOf(directory, sessionId);
        if (!Files.exists(legacy)) {
            return;
        }
        Path target = fileOf(directory, sessionId, CURRENT);
        if (Files.exists(target)) {
            Files.deleteIfExists(legacy);
            return;
        }
        Path tmp = directory.resolve(sessionId + ".v" + CURRENT + SUFFIX + ".tmp");
        Files.deleteIfExists(tmp);
        Files.writeString(tmp, headerLine(sessionId, CURRENT) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.write(tmp, Files.readAllBytes(legacy), StandardOpenOption.APPEND);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, target);
        }
        Files.deleteIfExists(legacy);
    }

    /** First non-blank line of {@code file} parsed as a header, or {@code null}. */
    private static Header firstHeader(Path file) throws IOException {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank())
                    .findFirst()
                    .map(SessionFileFormat::parseHeader)
                    .orElse(null);
        }
    }

    /** Format header of a session file. */
    public record Header(int version, String sessionId) {
    }

    /** Header-only metadata of a session file (no event parsing). */
    public record Stat(String sessionId, int version, long sizeBytes) {
    }
}
