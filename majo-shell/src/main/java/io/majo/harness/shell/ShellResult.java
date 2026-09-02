package io.majo.harness.shell;

/**
 * The outcome of one shell run: exit code plus captured stdout/stderr.
 */
public record ShellResult(int exitCode, String stdout, String stderr) {

    /** Whether the shell exited with the conventional success code. */
    public boolean ok() {
        return exitCode == 0;
    }
}
