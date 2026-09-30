package io.majo.harness.fs;

import io.majo.harness.ssh.SshExec;
import io.majo.harness.ssh.SshTarget;
import java.util.ArrayList;
import java.util.List;

/**
 * Remote {@link FsProvider} over SSH (dsh `fs-ssh` analog): text reads
 * become {@code cat}, writes become {@code tee}, globs become {@code find}.
 * Operates on the remote host's filesystem — paths are remote paths, and
 * local path access is never inferred from them (dsh invariant).
 *
 * <p>The target's OpenSSH argv prefix is shared with the subprocess
 * provider, so fs and command execution see the same remote world.
 */
public final class SshFsProvider implements FsProvider {

    private final SshExec ssh;

    public SshFsProvider(SshExec ssh) {
        this.ssh = ssh;
    }

    @Override
    public String readText(String path) {
        var result = ssh.exec("cat " + SshExec.shQuote(path));
        if (result.exitCode() != 0) {
            throw new FsException("cannot read " + path + ": " + result.stderr().strip());
        }
        return result.stdout();
    }

    @Override
    public void writeText(String path, String content) {
        var result = ssh.execWithStdin("tee " + SshExec.shQuote(path) + " > /dev/null",
                content);
        if (result.exitCode() != 0) {
            throw new FsException("cannot write " + path + ": " + result.stderr().strip());
        }
    }

    /**
     * Recursive glob on the remote world: one {@code find root -type f}
     * listing, filtered in-memory with the shared glob dialect
     * ({@link FsProvider#globToRegex}) so local and remote semantics are
     * identical (find's own {@code -path} wildcards would cross directories
     * differently).
     */
    @Override
    public List<String> glob(String root, String pattern) {
        var result = ssh.exec("find " + SshExec.shQuote(root) + " -type f");
        if (result.exitCode() != 0) {
            throw new FsException("cannot glob " + root + "/" + pattern + ": "
                    + result.stderr().strip());
        }
        java.util.regex.Pattern matcher = FsProvider.globToRegex(pattern);
        List<String> matches = new ArrayList<>();
        for (String line : result.stdout().split("\n")) {
            String path = line.strip();
            if (path.isEmpty()) {
                continue;
            }
            String relative = path.startsWith(root + "/")
                    ? path.substring(root.length() + 1) : path;
            if (matcher.matcher(relative).matches()) {
                matches.add(path);
            }
        }
        matches.sort(String::compareTo);
        return List.copyOf(matches);
    }

    /**
     * Remote recursive grep ({@code grep -rn}, GNU {@code --include} when an
     * include glob is given). Exit code 1 means "no matches", not an error;
     * "Binary file … matches" lines carry no text and are dropped.
     */
    @Override
    public List<String> grep(String path, String regex, String include, int headLimit) {
        StringBuilder command = new StringBuilder("grep -rn -e ")
                .append(SshExec.shQuote(regex));
        if (include != null && !include.isBlank()) {
            command.append(" --include=").append(SshExec.shQuote(include));
        }
        command.append(' ').append(SshExec.shQuote(path));
        var result = ssh.exec(command.toString());
        if (result.exitCode() != 0 && result.exitCode() != 1) {
            throw new FsException("cannot grep " + path + ": " + result.stderr().strip());
        }
        List<String> hits = new ArrayList<>();
        for (String line : result.stdout().split("\n")) {
            if (line.isBlank() || line.startsWith("Binary file ")) {
                continue;
            }
            hits.add(line);
            if (hits.size() >= headLimit) {
                break;
            }
        }
        return List.copyOf(hits);
    }
}
