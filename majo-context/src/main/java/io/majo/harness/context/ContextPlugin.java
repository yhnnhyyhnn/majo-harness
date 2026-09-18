package io.majo.harness.context;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Request-context injection (roadmap-0.5 / the dsh `context` family): once
 * per session, at the first turn start, the plugin durably appends a
 * {@code CONTEXT_NOTE} carrying workspace instructions (AGENTS.md /
 * CLAUDE.md) and the current time. The note enters the log as model-visible
 * user-role content — it replays, survives resume, and compacts like any
 * other context ("model-visible means logged"); the title/compaction
 * bookkeeping skips it, derivation includes it.
 *
 * <p>Dedupe is two-layer: an in-memory set for this process, and a marker
 * scan of the session log on first sight (so restarted hosts do not
 * re-inject into sessions that already carry the note).
 *
 * <p>Config: {@code {dir: ".", files: [AGENTS.md, CLAUDE.md], timeContext:
 * true, maxChars: 8000}} — files are read at mount time; missing files are
 * simply skipped.
 */
public final class ContextPlugin implements Plugin {

    public static final String NAME = "context";
    public static final String MARKER = "[context] ";

    static final Logger LOG = LoggerFactory.getLogger(ContextPlugin.class);

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Sessions whose context note this process already handled. */
    private final Set<String> handled = ConcurrentHashMap.newKeySet();

    @Override
    public Object apply(Context ctx, Object config) {
        Map<?, ?> map = config instanceof Map<?, ?> m ? m : Map.of();
        Path dir = Path.of(map.get("dir") == null ? "." : String.valueOf(map.get("dir")));
        List<String> files = new ArrayList<>();
        if (map.get("files") instanceof List<?> list) {
            for (Object item : list) {
                files.add(String.valueOf(item));
            }
        } else {
            files = List.of("AGENTS.md", "CLAUDE.md");
        }
        boolean timeContext = !Boolean.FALSE.equals(map.get("timeContext"));
        int maxChars = map.get("maxChars") instanceof Number number && number.intValue() > 0
                ? number.intValue()
                : 8_000;

        String instructions = readInstructions(dir, files);
        SessionService sessions = ctx.get(SessionService.NAME);
        Disposable listener = ctx.on(SessionService.EVENT, (thisArg, args) -> {
            SessionEvent event = (SessionEvent) args[1];
            if (event.type() != SessionEventType.TURN_START) {
                return null;
            }
            String sessionId = String.valueOf(args[0]);
            if (!handled.add(sessionId)) {
                return null;
            }
            if (alreadyInjected(sessions, sessionId)) {
                return null;
            }
            String note = buildNote(instructions, timeContext, maxChars);
            if (note != null) {
                sessions.append(sessionId, SessionEventType.CONTEXT_NOTE,
                        Map.of(SessionEvent.FIELD_CONTENT, MARKER + note));
                LOG.info("context: injected {} chars into session \"{}\"", note.length(),
                        sessionId);
            }
            return null;
        });
        return listener;
    }

    /** Concatenated instruction files (truncated), or {@code null} when none. */
    private static String readInstructions(Path dir, List<String> files) {
        StringBuilder text = new StringBuilder();
        for (String name : files) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String content = Files.readString(file).strip();
                if (!content.isEmpty()) {
                    if (text.length() > 0) {
                        text.append("\n\n");
                    }
                    text.append("# ").append(name).append("\n").append(content);
                }
            } catch (IOException e) {
                LOG.warn("context: cannot read {}: {}", file, e.getMessage());
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    /**
     * Whether the durable log already carries this plugin's note (a
     * {@code CONTEXT_NOTE} starting with the marker) — the restart-safe half
     * of dedupe.
     */
    private static boolean alreadyInjected(SessionService sessions, String sessionId) {
        return sessions.events(sessionId).stream()
                .anyMatch(event -> event.type() == SessionEventType.CONTEXT_NOTE
                        && event.content() != null
                        && event.content().startsWith(MARKER));
    }

    private static String buildNote(String instructions, boolean timeContext, int maxChars) {
        StringBuilder note = new StringBuilder();
        if (instructions != null) {
            note.append("workspace instructions:\n").append(instructions);
        }
        if (timeContext) {
            if (note.length() > 0) {
                note.append("\n\n");
            }
            note.append("current time: ")
                    .append(LocalDateTime.now().format(TIME_FORMAT));
        }
        if (note.isEmpty()) {
            return null;
        }
        String text = note.toString();
        return text.length() > maxChars ? text.substring(0, maxChars - 3) + "..." : text;
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(SessionService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
