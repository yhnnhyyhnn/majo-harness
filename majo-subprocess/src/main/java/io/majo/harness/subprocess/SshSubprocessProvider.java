package io.majo.harness.subprocess;

import io.majo.harness.ssh.SshExec;
import io.majo.harness.ssh.SshTarget;
import java.util.Map;

/**
 * Remote {@link SubprocessProvider} over SSH (dsh `subprocess-ssh` analog):
 * argv executes on the remote host — the argv is shell-quoted and joined
 * into a single remote command, with optional {@code cd} and env exports
 * prepended. The target's OpenSSH argv prefix is shared with the fs
 * provider, so fs and command execution see the same remote world.
 */
public final class SshSubprocessProvider implements SubprocessProvider {

    private final SshExec ssh;

    public SshSubprocessProvider(SshExec ssh) {
        this.ssh = ssh;
    }

    @Override
    public ProcessResult run(Command command) {
        StringBuilder remote = new StringBuilder();
        if (command.cwd() != null && !command.cwd().isBlank()) {
            remote.append("cd ").append(SshExec.shQuote(command.cwd())).append(" && ");
        }
        for (Map.Entry<String, String> entry : command.env().entrySet()) {
            remote.append(entry.getKey()).append("=")
                    .append(SshExec.shQuote(entry.getValue())).append(" ");
        }
        for (String arg : command.argv()) {
            remote.append(SshExec.shQuote(arg)).append(" ");
        }
        long timeout = command.timeoutSeconds() > 0
                ? command.timeoutSeconds() : 60;
        var result = ssh.exec(remote.toString().strip());
        return new ProcessResult(result.exitCode(), result.stdout(), result.stderr());
    }
}
