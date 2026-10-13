package io.majo.harness.fs;

import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.io.IOException;
import java.util.Map;

/**
 * A model-facing {@code git_status} tool: shows modified, staged, and
 * untracked files in the working directory — lets the model understand its
 * own changes after writing files. Read-only, no approval required.
 */
public final class GitStatusTool implements Tool {

    @Override
    public ToolSpec spec() {
        return ToolSpec.of("git_status",
                "Shows the git working-tree status: modified, staged, and untracked files. "
                        + "Use after writing files to verify what changed.");
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            var proc = new ProcessBuilder("git", "status", "--porcelain");
            proc.redirectErrorStream(false);
            var process = proc.start();
            // drain both pipes before waiting: git blocks writing once a pipe
            // fills, so a waitFor-first order reports a healthy command as
            // "timed out" exactly when there is lots to show
            java.util.concurrent.CompletableFuture<byte[]> stdout =
                    drain(process.getInputStream());
            java.util.concurrent.CompletableFuture<byte[]> stderr =
                    drain(process.getErrorStream());
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("git_status: timed out");
            }
            String output = new String(stdout.join(), java.nio.charset.StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                return ToolResult.error("git_status: not a git repository or git not on PATH: "
                        + new String(stderr.join(), java.nio.charset.StandardCharsets.UTF_8).strip());
            }
            if (output.isEmpty()) {
                return ToolResult.ok("(clean — no changes)", Map.of());
            }
            return ToolResult.ok(output, Map.of());
        } catch (IOException e) {
            return ToolResult.error("git_status: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("git_status: interrupted");
        }
    }

    private static java.util.concurrent.CompletableFuture<byte[]> drain(java.io.InputStream stream) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (java.io.InputStream input = stream) {
                return input.readAllBytes();
            } catch (IOException e) {
                return new byte[0];
            }
        });
    }
}
