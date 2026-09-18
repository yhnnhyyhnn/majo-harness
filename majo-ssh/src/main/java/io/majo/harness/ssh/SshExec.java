package io.majo.harness.ssh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes commands on an {@link SshTarget} via the OpenSSH CLI. Every call
 * spawns a fresh {@code ssh} process carrying the target's argv prefix;
 * connection reuse is delegated to the user's OpenSSH configuration
 * (ControlMaster/ControlPersist in {@code ~/.ssh/config}).
 *
 * <p>The remote command is passed as a single argument after {@code --},
 * so local shell interpolation never reaches the remote side; the caller
 * is responsible for quoting within the remote command itself.
 */
public final class SshExec {

    /** A finished SSH execution. */
    public record SshResult(int exitCode, String stdout, String stderr) {
    }

    private final SshTarget target;
    private final long timeoutMillis;

    public SshExec(SshTarget target, long timeoutMillis) {
        this.target = target;
        this.timeoutMillis = timeoutMillis;
    }

    /** The execution target. */
    public SshTarget target() {
        return target;
    }

    /**
     * Runs a remote command and returns its result; a timeout or spawn
     * failure throws {@link SshException}.
     */
    public SshResult exec(String remoteCommand) {
        List<String> argv = new ArrayList<>(target.argvPrefix());
        argv.add("--");
        argv.add(remoteCommand);
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.redirectErrorStream(false);
            Process process = builder.start();
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new SshException("SSH command timed out after " + timeoutMillis
                        + " ms on " + target.label() + ": " + remoteCommand);
            }
            int exitCode = process.exitValue();
            String stdout = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return new SshResult(exitCode, stdout, stderr);
        } catch (IOException e) {
            throw new SshException("SSH spawn failed on " + target.label()
                    + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SshException("SSH command interrupted on " + target.label(), e);
        }
    }

    /** Runs a remote command with {@code stdin} piped to it. */
    public SshResult execWithStdin(String remoteCommand, String stdin) {
        List<String> argv = new ArrayList<>(target.argvPrefix());
        argv.add("--");
        argv.add(remoteCommand);
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.redirectErrorStream(false);
            Process process = builder.start();
            process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new SshException("SSH command timed out after " + timeoutMillis
                        + " ms on " + target.label() + ": " + remoteCommand);
            }
            int exitCode = process.exitValue();
            String stdout = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return new SshResult(exitCode, stdout, stderr);
        } catch (IOException e) {
            throw new SshException("SSH spawn failed on " + target.label()
                    + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SshException("SSH command interrupted on " + target.label(), e);
        }
    }

    /**
     * POSIX single-quote wrapper for embedding a path or value inside a
     * remote shell command — the remote side's shell does the interpolation,
     * not ours.
     */
    public static String shQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Runs a remote command, failing loudly on a non-zero exit. */
    public SshResult execChecked(String remoteCommand) {
        SshResult result = exec(remoteCommand);
        if (result.exitCode() != 0) {
            throw new SshException("SSH command failed on " + target.label()
                    + " (exit " + result.exitCode() + "): " + result.stderr());
        }
        return result;
    }
}
