package io.majo.harness.shell;

import io.majo.harness.sandbox.SandboxService;
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
 *
 * <p>When a sandbox is wired in, the adapter confines its argv through
 * {@code ctx.sandbox} before spawning (a consumer applying the sandbox wrap,
 * mirroring dsh).
 */
public final class LocalShellProvider implements ShellProvider {

    private final SubprocessService subprocess;
    private final ShellLauncher launcher;
    private final SandboxService sandbox;

    public LocalShellProvider(SubprocessService subprocess, ShellLauncher launcher) {
        this(subprocess, launcher, null);
    }

    public LocalShellProvider(SubprocessService subprocess, ShellLauncher launcher, SandboxService sandbox) {
        this.subprocess = subprocess;
        this.launcher = launcher;
        this.sandbox = sandbox;
    }

    @Override
    public ShellResult run(ShellCommand command) {
        java.util.List<String> argv = ShellCommand.launcherArgs(launcher, command);
        if (sandbox != null) {
            argv = sandbox.confine(argv);
        }
        Command subprocessCommand = new Command(argv, command.cwd(), command.env(), command.timeoutSeconds());
        try {
            ProcessResult result = subprocess.run(subprocessCommand);
            return new ShellResult(result.exitCode(), result.stdout(), result.stderr());
        } catch (SubprocessException e) {
            throw new ShellException("shell: " + e.getMessage(), e);
        }
    }
}
