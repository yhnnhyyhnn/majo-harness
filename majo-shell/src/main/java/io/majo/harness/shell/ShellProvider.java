package io.majo.harness.shell;

/**
 * The shell provider seam (Service Definition): implementations execute a
 * {@link ShellCommand script} against an execution world and capture the
 * result. Providers must throw {@link ShellException} for every failure.
 */
public interface ShellProvider {

    ShellResult run(ShellCommand command);
}
