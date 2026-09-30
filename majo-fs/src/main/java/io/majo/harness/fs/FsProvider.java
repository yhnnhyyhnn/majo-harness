package io.majo.harness.fs;

import java.util.List;

/**
 * The fs provider seam (Service Definition): implementations execute text
 * reads, text writes, and glob listing against an execution world. The local
 * implementation ships in this module; remote/sandboxed providers implement
 * the same interface so policy and tool consumers never fork.
 *
 * <p>Providers operate on absolute path strings and must throw
 * {@link FsException} for every failure.
 */
public interface FsProvider {

    /** Reads a text file; missing paths or directories fail loudly. */
    String readText(String path);

    /** Writes a text file, creating parent directories as needed. */
    void writeText(String path, String content);

    /** Lists paths under {@code root} matching a glob pattern (absolute, sorted). */
    List<String> glob(String root, String pattern);

    /**
     * Searches text file content under {@code path} (a file or a directory,
     * recursed) for {@code regex}; returns at most {@code headLimit} lines
     * formatted {@code path:line:text}. {@code include} (nullable) filters
     * matched files by name glob. Binary files are skipped.
     */
    List<String> grep(String path, String regex, String include, int headLimit);

    /**
     * The shared glob dialect (dsh parity): a double star crosses directory
     * boundaries, and a double star followed by a slash also matches zero
     * directories; a single star and question mark stay inside one segment;
     * character classes and brace alternation pass through. Patterns are
     * matched against the path relative to the search root with {@code /}
     * separators, so both provider worlds behave identically.
     */
    static java.util.regex.Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                        regex.append("(?:[^/]*/)*"); // "**/" matches zero or more segments
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else if (c == '[' || c == ']' || c == '{' || c == '}') {
                regex.append(c); // character classes and brace alternation pass through
            } else if ("\\.^$+()|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return java.util.regex.Pattern.compile(regex.toString());
    }
}
