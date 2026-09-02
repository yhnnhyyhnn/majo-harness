package io.majo.harness.shell;

import io.majo.harness.subprocess.Command;
import io.majo.harness.subprocess.ProcessResult;
import io.majo.harness.subprocess.SubprocessException;
import io.majo.harness.subprocess.SubprocessService;

/**
 * The local shell provider — an Adapter over the subprocess seam: scripts are
 * wrapped into {@link ShellLauncher argv} and executed through
 * {@code ctx.subprocess}, mapping {@link ProcessResult} to {@link ShellResult}
 * and {@link SubprocessException} to {@link ShellException}. Shell consumers
 * never see subprocess types; swapping in a remote shell provider only changes
 * this adapter's backend.
 */
public final class LocalShellProvider implements ShellProvider {

    private final SubprocessService subprocess;
    private final ShellLauncher launcher;

    public LocalShellProvider(SubprocessService subprocess, ShellLauncher launcher) {
        this.subprocess = subprocess;
        this.launcher = launcher;
    }

    @Override
    public ShellResult run(ShellCommand command) {
        Command argv = new Command(ShellCommand.launcherArgs(launcher, command),
                command.cwd(), command.env(), command.timeoutSeconds());
        try {
            ProcessResult result = subprocess.run(argv);
            return new ShellResult(result.exitCode(), result.stdout(), result.stderr());
        } catch (SubprocessException e) {
            throw new ShellException("shell: " + e.getMessage(), e);
        }
    }
}
