package io.majo.harness.subprocess;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Local {@link SubprocessProvider} over {@link ProcessBuilder}: argv in,
 * captured stdout/stderr out, timeout enforced by destroy. Output streams are
 * drained on virtual threads so large output cannot deadlock the wait.
 */
public final class LocalSubprocessProvider implements SubprocessProvider {

    private static final ExecutorService STREAM_READERS = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public ProcessResult run(Command command) {
        if (command.argv().isEmpty()) {
            throw new SubprocessException("subprocess: empty argv");
        }
        if (command.timeoutSeconds() < 1) {
            // the service resolves the configured default before reaching a
            // provider; a provider call without a positive timeout is misuse
            throw new SubprocessException("subprocess: command requires a positive timeoutSeconds");
        }
        ProcessBuilder builder = new ProcessBuilder(command.argv());
        if (command.cwd() != null) {
            builder.directory(Path.of(command.cwd()).toFile());
        }
        if (!command.env().isEmpty()) {
            builder.environment().putAll(command.env());
        }
        try {
            Process process = builder.start();
            CompletableFuture<byte[]> stdout = drain(process.getInputStream());
            CompletableFuture<byte[]> stderr = drain(process.getErrorStream());
            CompletableFuture<Void> stdinFeed = feedStdin(process, command.stdin());
            boolean finished = process.waitFor(command.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                throw new SubprocessException("subprocess timed out after " + command.timeoutSeconds()
                        + "s: " + command.argv().get(0));
            }
            int exit = process.exitValue();
            stdinFeed.join(); // surface a broken-pipe write as an IOException path
            return new ProcessResult(exit,
                    decode(stdout.join()), decode(stderr.join()));
        } catch (IOException e) {
            throw new SubprocessException("subprocess cannot start " + command.argv().get(0)
                    + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SubprocessException("subprocess interrupted: " + command.argv().get(0), e);
        }
    }

    private static CompletableFuture<byte[]> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream input = stream) {
                return input.readAllBytes();
            } catch (IOException e) {
                return new byte[0];
            }
        }, STREAM_READERS);
    }

    /**
     * Writes the stdin payload and closes the pipe (async, so a child that
     * never reads cannot deadlock the wait); {@code null} stdin just closes
     * the pipe. A broken pipe means the child exited before reading — the
     * exit code still governs.
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
        }, STREAM_READERS);
    }

    private static String decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
