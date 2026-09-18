package io.majo.harness.context;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The {@code @file} mention seam ({@code ctx.fileReferences}, dsh
 * `file-reference` analog): the user references a file in the composer with
 * {@code @path}; the referenced content is injected into the session log as
 * a durable {@code CONTEXT_NOTE} so the model reads it without a tool
 * round-trip, and it replays/compacts like any other context.
 *
 * <p>Text-only (a NUL byte in the prologue rejects the file), truncated at
 * {@link #MAX_CHARS}; suggestions walk the process working directory,
 * skipping VCS/build noise.
 */
public final class FileReferencesService extends Service {

    public static final String NAME = "fileReferences";
    public static final int MAX_CHARS = 64_000;
    public static final int SUGGEST_LIMIT = 20;
    private static final int BINARY_PROBE_BYTES = 8_192;
    private static final List<String> SKIP_DIRS =
            List.of(".git", "node_modules", "target", "dist", "build", ".idea");

    private final SessionService sessions;
    private final Path root;

    public FileReferencesService(Context ctx, SessionService sessions) {
        this(ctx, sessions, Path.of("."));
    }

    public FileReferencesService(Context ctx, SessionService sessions, Path workspace) {
        super(ctx, NAME);
        this.sessions = sessions;
        this.root = workspace.toAbsolutePath().normalize();
    }

    /** One suggest hit: workspace-relative path + size. */
    public record Suggestion(String path, long size) {
    }

    /**
     * Files under the working directory whose relative path contains
     * {@code query} (case-insensitive); empty query lists the shallow tree.
     */
    public List<Suggestion> suggest(String query) {
        String needle = query == null ? "" : query.toLowerCase();
        List<Suggestion> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 4)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !isNoise(path))
                    .sorted()
                    .forEach(path -> {
                        if (hits.size() >= SUGGEST_LIMIT) {
                            return;
                        }
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        if (needle.isEmpty() || relative.toLowerCase().contains(needle)) {
                            try {
                                hits.add(new Suggestion(relative, Files.size(path)));
                            } catch (IOException ignored) {
                                // raced deletion: skip
                            }
                        }
                    });
        } catch (IOException e) {
            // unreadable cwd: no suggestions
        }
        return List.copyOf(hits);
    }

    /**
     * Injects the referenced file into {@code sessionId} as a durable
     * {@code CONTEXT_NOTE} ({@code [context] @path:…}) and returns the
     * injected character count. Unknown/blank ids fail loudly.
     */
    public int inject(String sessionId, String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("mention: pass a file path");
        }
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("mention: not a file: " + path);
        }
        byte[] prologue = prologue(file);
        for (byte b : prologue) {
            if (b == 0) {
                throw new IllegalArgumentException(
                        "mention: \"" + path + "\" looks binary; use read_file instead");
            }
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        boolean truncated = content.length() > MAX_CHARS;
        if (truncated) {
            content = content.substring(0, MAX_CHARS) + "\n[... truncated at "
                    + MAX_CHARS + " chars ...]";
        }
        sessions.append(sessionId, SessionEventType.CONTEXT_NOTE,
                Map.of(SessionEvent.FIELD_CONTENT,
                        ContextPlugin.MARKER + "@" + path + ":\n" + content));
        return content.length();
    }

    private static byte[] prologue(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            return input.readNBytes(BINARY_PROBE_BYTES);
        }
    }

    private static boolean isNoise(Path path) {
        for (Path part : path) {
            if (SKIP_DIRS.contains(part.toString()) || part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }
}
