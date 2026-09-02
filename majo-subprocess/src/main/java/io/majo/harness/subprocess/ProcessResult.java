package io.majo.harness.subprocess;

/**
 * The outcome of one subprocess run: exit code plus captured stdout/stderr
 * (decoded as UTF-8).
 */
public record ProcessResult(int exitCode, String stdout, String stderr) {

    /** Whether the process exited with the conventional success code. */
    public boolean ok() {
        return exitCode == 0;
    }
}
