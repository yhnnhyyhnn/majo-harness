package io.majo.harness.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 *
 * <p>Output streams are drained on virtual threads so large remote output
 * cannot fill the OS pipe buffer and deadlock the wait, and stdin is fed
 * asynchronously so a child that exits without reading it cannot block.
 */
public final class SshExec {

    /** A finished SSH execution. */
    public record SshResult(int exitCode, String stdout, String stderr) {
    }

    private static final ExecutorService STREAM_WORKERS = Executors.newVirtualThreadPerTaskExecutor();

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
        return run(remoteCommand, null, timeoutMillis);
    }

    /** Like {@link #exec(String)} with a per-call timeout in millis. */
    public SshResult exec(String remoteCommand, long timeoutMillis) {
        return run(remoteCommand, null, timeoutMillis);
    }

    /** Runs a remote command with {@code stdin} piped to it. */
    public SshResult execWithStdin(String remoteCommand, String stdin) {
        return run(remoteCommand, stdin, timeoutMillis);
    }

    /** Like {@link #execWithStdin(String, String)} with a per-call timeout in millis. */
    public SshResult execWithStdin(String remoteCommand, String stdin, long timeoutMillis) {
        return run(remoteCommand, stdin, timeoutMillis);
    }

    private SshResult run(String remoteCommand, String stdin, long timeout) {
        List<String> argv = new ArrayList<>(target.argvPrefix());
        argv.add("--");
        argv.add(remoteCommand);
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.redirectErrorStream(false);
            process = builder.start();
        } catch (IOException e) {
            throw new SshException("SSH spawn failed on " + target.label()
                    + ": " + e.getMessage(), e);
        }
        CompletableFuture<byte[]> stdout = drain(process.getInputStream());
        CompletableFuture<byte[]> stderr = drain(process.getErrorStream());
        CompletableFuture<Void> stdinFeed = feedStdin(process, stdin);
        try {
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                throw new SshException("SSH command timed out after " + timeout
                        + " ms on " + target.label() + ": " + remoteCommand);
            }
            int exitCode = process.exitValue();
            stdinFeed.join(); // keep the write's lifecycle ordered; a broken pipe is benign
            return new SshResult(exitCode, decode(stdout.join()), decode(stderr.join()));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new SshException("SSH command interrupted on " + target.label(), e);
        }
    }

    private static CompletableFuture<byte[]> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream input = stream) {
                return input.readAllBytes();
            } catch (IOException e) {
                return new byte[0];
            }
        }, STREAM_WORKERS);
    }

    /**
     * Writes the stdin payload and closes the pipe (async, so a remote command
     * that never reads cannot block before the timeout even starts); {@code null}
     * stdin just closes the pipe. A broken pipe means the child exited without
     * reading stdin — its exit code governs.
     */
    private static CompletableFuture<Void> feedStdin(Process process, String stdin) {
        return CompletableFuture.runAsync(() -> {
            try (java.io.OutputStream output = process.getOutputStream()) {
                if (stdin != null) {
                    output.write(stdin.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            } catch (IOException ignored) {
                // the child exited without reading stdin: its exit code governs
            }
        }, STREAM_WORKERS);
    }

    private static String decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
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
