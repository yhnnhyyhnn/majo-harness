package io.majo.harness.fs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Local {@link FsProvider} over {@code java.nio.file}: absolute path strings
 * in, text content out, every I/O failure wrapped as {@link FsException}.
 */
public final class LocalFsProvider implements FsProvider {

    /** Files larger than this are skipped by grep (they are data, not source). */
    private static final long GREP_MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final int GREP_MAX_LINE_CHARS = 2000;

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

    /**
     * Recursive walk matched against each file's path relative to {@code root}
     * using the shared glob dialect ({@link FsProvider#globToRegex}).
     */
    @Override
    public List<String> glob(String root, String pattern) {
        try {
            Path base = Path.of(requirePath(root));
            java.util.regex.Pattern matcher = FsProvider.globToRegex(pattern);
            List<String> matches = new ArrayList<>();
            try (Stream<Path> paths = Files.walk(base)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> matcher.matcher(
                                base.relativize(path).toString().replace('\\', '/')).matches())
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

    @Override
    public List<String> grep(String path, String regex, String include, int headLimit) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new FsException("fs: invalid grep pattern: " + e.getMessage());
        }
        Path base = Path.of(requirePath(path));
        try {
            List<String> hits = new ArrayList<>();
            if (Files.isRegularFile(base)) {
                grepFile(base, pattern, hits, headLimit);
            } else if (Files.isDirectory(base)) {
                try (Stream<Path> paths = Files.walk(base)) {
                    Iterator<Path> files = paths.filter(Files::isRegularFile)
                            .filter(file -> include == null || include.isBlank()
                                    || FsProvider.globToRegex(include)
                                            .matcher(file.getFileName().toString()).matches())
                            .sorted(java.util.Comparator.comparing(Path::toString))
                            .iterator();
                    while (files.hasNext() && hits.size() < headLimit) {
                        grepFile(files.next(), pattern, hits, headLimit);
                    }
                }
            } else {
                throw new FsException("fs: cannot grep missing path \"" + path + "\"");
            }
            return List.copyOf(hits);
        } catch (IOException e) {
            throw new FsException("fs: cannot grep \"" + path + "\": " + e.getMessage(), e);
        }
    }

    private static void grepFile(Path file, Pattern pattern, List<String> hits, int headLimit)
            throws IOException {
        if (Files.size(file) > GREP_MAX_FILE_BYTES || isBinary(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            long number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (hits.size() >= headLimit) {
                    return;
                }
                if (pattern.matcher(line).find()) {
                    String text = line.length() > GREP_MAX_LINE_CHARS
                            ? line.substring(0, GREP_MAX_LINE_CHARS) + "…" : line;
                    hits.add(file.toAbsolutePath() + ":" + number + ":" + text);
                }
            }
        }
    }

    /** NUL byte in the first 8 KiB marks a non-text file. */
    private static boolean isBinary(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] probe = in.readNBytes(8192);
            for (byte b : probe) {
                if (b == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String requirePath(String path) {
        return Objects.requireNonNull(path, "fs: path must not be null");
    }
}
