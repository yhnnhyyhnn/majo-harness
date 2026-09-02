package io.majo.harness.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MajoCliTest {

    private record Result(int code, String stdout, String stderr) {}

    private static Result run(String... args) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int code = new MajoCli(List.of(args),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8)).run();
        return new Result(code, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void missingTaskIsAUsageError() {
        Result result = run();
        assertThat(result.code()).isEqualTo(2);
        assertThat(result.stderr()).contains("usage: majo");
        assertThat(result.stderr()).contains("task");
    }

    @Test
    void unknownOptionIsAUsageError() {
        Result result = run("--nope", "task");
        assertThat(result.code()).isEqualTo(2);
        assertThat(result.stderr()).contains("unknown option: --nope");
    }

    @Test
    void helpAndProfilesListExitZero() {
        Result help = run("--help");
        assertThat(help.code()).isZero();
        assertThat(help.stdout()).contains("usage: majo");

        Result profiles = run("--profiles");
        assertThat(profiles.code()).isZero();
        assertThat(profiles.stdout()).contains("headless");
    }

    @Test
    void runsTheBuiltinHeadlessProfileEndToEnd() {
        // offline: the built-in profile composes the mock model + calc tool
        Result result = run("1+2");
        assertThat(result.code()).isZero();
        assertThat(result.stdout()).contains("TURN_START");
        assertThat(result.stdout()).contains("calculated: 3");
        assertThat(result.stdout()).contains("answer: calculated: 3");
        assertThat(result.stderr()).isEmpty();
    }
}
