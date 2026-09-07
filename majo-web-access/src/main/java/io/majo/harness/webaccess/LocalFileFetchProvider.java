package io.majo.harness.webaccess;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Offline fetch backend ({@code local-file}) for demos and offline tests:
 * serves files from a fixed root directory as readable text. URLs may look
 * like {@code file:<name>} or be plain relative names; absolute paths and
 * {@code ..} traversal are rejected, so the provider can never read outside
 * its root. Missing files fail loudly with a {@link WebAccessException}.
 */
public final class LocalFileFetchProvider implements FetchProvider {

    public static final String PROVIDER_NAME = "local-file";

    private final Path root;

    public LocalFileFetchProvider(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public WebFetchResult fetch(WebFetchRequest request) {
        String raw = request.url();
        String name = raw;
        if (name.startsWith("file:")) {
            name = name.substring("file:".length());
        }
        if (name.startsWith("/") || name.contains("..")) {
            throw new WebAccessException(
                    "local-file: absolute or traversal paths are not allowed: \"" + raw + "\"");
        }
        Path file = root.resolve(name).normalize();
        if (!file.startsWith(root)) {
            throw new WebAccessException("local-file: outside root: \"" + raw + "\"");
        }
        if (!Files.isRegularFile(file)) {
            throw new WebAccessException("local-file: no such demo file \"" + name + "\" under "
                    + root);
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return new WebFetchResult("file:" + name,
                    file.getFileName().toString(), text);
        } catch (java.io.IOException e) {
            throw new WebAccessException("local-file: cannot read \"" + name + "\": "
                    + e.getMessage());
        }
    }

    /** Convenience for configs that pass a string root. */
    public static Path resolveRoot(Object configured) {
        if (configured == null || String.valueOf(configured).isBlank()) {
            throw new IllegalArgumentException("web-fetch-local: config \"root\" is required");
        }
        return Paths.get(String.valueOf(configured));
    }
}
