package io.majo.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The user-settings service ({@code ctx.settings}): a string key/value store
 * (validated keys, non-null values). With a configured file path the store is
 * file-backed (JSON object, write-through after every set — the file provider
 * of the settings capability); without one it is in-memory only. A corrupt or
 * unreadable settings file fails loudly at startup.
 */
public final class SettingsService extends Service {

    public static final String NAME = "settings";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final Path file;

    public SettingsService(Context ctx, Path file) {
        super(ctx, NAME);
        this.file = file;
        if (file != null) {
            load(file);
        }
    }

    private void load(Path file) {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("settings: file is not a JSON object: " + file);
            }
            root.fields().forEachRemaining(entry ->
                    values.put(entry.getKey(), entry.getValue().isTextual()
                            ? entry.getValue().asText()
                            : entry.getValue().toString()));
        } catch (IOException e) {
            throw new IllegalStateException("settings: cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** The value for {@code key}, or {@code null} when unset. */
    public String get(String key) {
        requireKey(key);
        Map<String, String> overrides = OVERRIDES.get();
        if (overrides != null && overrides.containsKey(key)) {
            return overrides.get(key);
        }
        return values.get(key);
    }

    /** Sets a value (write-through to the file when configured). */
    public void set(String key, String value) {
        requireKey(key);
        if (value == null) {
            throw new IllegalArgumentException("settings: value for \"" + key + "\" must not be null");
        }
        values.put(key, value);
        persist();
    }

    /** Removes {@code key} (no-op when unset). */
    public void unset(String key) {
        requireKey(key);
        if (values.remove(key) != null) {
            persist();
        }
    }

    /** All stored keys starting with {@code prefix} (keys untouched). */
    public synchronized Map<String, String> entries(String prefix) {
        Map<String, String> matches = new java.util.TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                matches.put(entry.getKey(), entry.getValue());
            }
        }
        return java.util.Collections.unmodifiableMap(matches);
    }

    /** The stored keys, sorted. */
    public List<String> keys() {
        return List.copyOf(new TreeMap<>(values).keySet());
    }

    /** Whether the store is file-backed. */
    public boolean isPersistent() {
        return file != null;
    }

    /**
     * Scoped per-agent overrides (roadmap A2): while {@link #scoped} runs, every
     * {@code get} on any SettingsService instance sees these values first; the
     * thread-local is always torn down. Null values are ignored (no key wins).
     */
    private static final ThreadLocal<java.util.Map<String, String>> OVERRIDES = new ThreadLocal<>();

    /** Runs {@code body} with per-scope setting overrides, restoring state after. */
    public static <T> T scoped(Map<String, String> overrides, java.util.function.Supplier<T> body) {
        if (overrides == null || overrides.isEmpty()) {
            return body.get();
        }
        java.util.Map<String, String> previous = OVERRIDES.get();
        java.util.Map<String, String> merged = new java.util.HashMap<>();
        if (previous != null) {
            merged.putAll(previous);
        }
        merged.putAll(overrides);
        OVERRIDES.set(java.util.Collections.unmodifiableMap(merged));
        try {
            return body.get();
        } finally {
            if (previous == null) {
                OVERRIDES.remove();
            } else {
                OVERRIDES.set(previous);
            }
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("settings: key must not be blank");
        }
    }

    private void persist() {
        if (file == null) {
            return;
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            ObjectNode root = MAPPER.createObjectNode();
            for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
                root.put(entry.getKey(), entry.getValue());
            }
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            // owner-only read/write on POSIX (best effort; unsupported elsewhere)
            try {
                Files.setPosixFilePermissions(file, java.util.Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX filesystem — nothing to restrict
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanup) {
                // original failure wins
            }
            throw new IllegalStateException("settings: cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
