package io.majo.harness.spill;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The spill store ({@code ctx.spillStore}, dsh `spill` analog): saves
 * oversized text out-of-band under the store directory and hands back a
 * short id — the durable locator the model passes to {@code spill_read}.
 * Files are plain text, one spill per file; nothing is ever deleted by the
 * store (sessions own their lifetime).
 */
public final class SpillStore extends Service {

    public static final String NAME = "spill";

    private final Path directory;

    public SpillStore(Context ctx, Path directory) throws IOException {
        super(ctx, NAME);
        this.directory = directory;
        Files.createDirectories(directory);
    }

    /** Saves {@code content} and returns its locator id. */
    public String save(String content) throws IOException {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Files.writeString(directory.resolve(id + ".txt"), content,
                StandardCharsets.UTF_8);
        return id;
    }

    /** The stored content for {@code id}, or {@code null} when unknown. */
    public String read(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}")) {
            return null;
        }
        Path file = directory.resolve(id + ".txt");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** The store directory (diagnostics). */
    public Path directory() {
        return directory;
    }
}
