package io.majo.harness.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Linux-only integration: when the {@code bwrap} executable is available the
 * sandbox really confines a child process. Skipped elsewhere/missing so the
 * suite stays portable.
 */
@EnabledOnOs(OS.LINUX)
class BwrapLinuxIntegrationTest {

    @Test
    void bwrapActuallyRunsAConfinedCommandWhenPresent() throws Exception {
        Assumptions.assumeTrue(findBwrap() != null, "bwrap not on PATH — skipping");

        BwrapSandboxProvider bwrap = new BwrapSandboxProvider(findBwrap(), List.of(
                "--die-with-parent", "--new-session",
                "--ro-bind", "/usr", "/usr",
                "--ro-bind", "/bin", "/bin",
                "--ro-bind", "/lib", "/lib",
                "--ro-bind", "/lib64", "/lib64",
                "--ro-bind", "/etc/ld.so.cache", "/etc/ld.so.cache",
                "--proc", "/proc",
                "--dev", "/dev"));
        Process process = new ProcessBuilder(bwrap.confine(List.of("/bin/sh", "-c", "echo sandboxed"))
                .toArray(new String[0]))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        if (code != 0) {
            // GitHub-hosted runners may forbid unprivileged user namespaces;
            // that is an environment limitation, not a sandbox regression.
            Assumptions.assumeTrue(!deniedByEnvironment(output),
                    "bwrap installed but unprivileged user namespaces are unavailable here:\n"
                            + output.trim());
        }
        assertThat(code).isZero();
        assertThat(output.trim()).isEqualTo("sandboxed");

        // the confined child cannot see an unrelated host path
        Path host = Files.createTempFile("majo-sandbox-", ".txt");
        Files.writeString(host, "secret");
        Process denied = new ProcessBuilder(bwrap.confine(List.of(
                "/bin/sh", "-c", "test -e '" + host + "' && echo visible || echo hidden"))
                .toArray(new String[0]))
                .start();
        String deniedOutput = new String(denied.getInputStream().readAllBytes());
        assertThat(denied.waitFor()).isZero();
        assertThat(deniedOutput.trim()).isEqualTo("hidden");
    }

    private static boolean deniedByEnvironment(String combinedOutput) {
        String lower = combinedOutput.toLowerCase();
        return lower.contains("operation not permitted")
                || (lower.contains("namespace") && (lower.contains("permission")
                        || lower.contains("allow") || lower.contains("denied")))
                || lower.contains("userns");
    }

    private static String findBwrap() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir, "bwrap");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }
}
