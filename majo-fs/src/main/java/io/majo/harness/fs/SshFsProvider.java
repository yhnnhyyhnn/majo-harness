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

    @Override
    public List<String> glob(String root, String pattern) {
        // find -path with glob metacharacters converted: * → *, ? → ?
        // (find -path already uses shell-style wildcards)
        String globPath = root + "/" + pattern;
        var result = ssh.exec("find " + SshExec.shQuote(root)
                + " -path " + SshExec.shQuote(globPath) + " -type f");
        if (result.exitCode() != 0) {
            throw new FsException("cannot glob " + root + "/" + pattern + ": "
                    + result.stderr().strip());
        }
        List<String> paths = new ArrayList<>();
        for (String line : result.stdout().split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                paths.add(trimmed);
            }
        }
        paths.sort(String::compareTo);
        return List.copyOf(paths);
    }
}
