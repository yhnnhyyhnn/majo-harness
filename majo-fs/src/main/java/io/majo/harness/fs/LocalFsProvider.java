package io.majo.harness.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Local {@link FsProvider} over {@code java.nio.file}: absolute path strings
 * in, text content out, every I/O failure wrapped as {@link FsException}.
 */
public final class LocalFsProvider implements FsProvider {

    @Override
    public String readText(String path) {
        try {
            Path file = Path.of(requirePath(path));
            if (Files.isDirectory(file)) {
                throw new FsException("fs: cannot read directory \"" + path + "\"");
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FsException("fs: cannot read \"" + path + "\": " + e.getMessage(), e);
        }
    }

    @Override
    public void writeText(String path, String content) {
        try {
            Path file = Path.of(requirePath(path));
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FsException("fs: cannot write \"" + path + "\": " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> glob(String root, String pattern) {
        try {
            Path base = Path.of(requirePath(root));
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> matches = new ArrayList<>();
            try (Stream<Path> paths = Files.list(base)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> matcher.matches(path.getFileName()))
                        .map(Path::toAbsolutePath)
                        .map(Path::toString)
                        .sorted()
                        .forEach(matches::add);
            }
            return List.copyOf(matches);
        } catch (IOException e) {
            throw new FsException("fs: cannot glob \"" + root + "\" for \"" + pattern + "\": " + e.getMessage(), e);
        }
    }

    private static String requirePath(String path) {
        return Objects.requireNonNull(path, "fs: path must not be null");
    }
}
