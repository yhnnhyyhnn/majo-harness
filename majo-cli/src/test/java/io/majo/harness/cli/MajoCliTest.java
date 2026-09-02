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
        return runWithInput(List.of(args), null);
    }

    private static Result runWithInput(List<String> args, String stdin) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int code = new MajoCli(args,
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8))
                .run(stdin == null ? null : new java.io.BufferedReader(
                        new java.io.StringReader(stdin)));
        return new Result(code, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void chatDrivesMultiTurnConversation() {
        // two user messages flow through the same session (multi-turn)
        Result result = runWithInput(List.of("chat"), "1+2\nand now 3+4\nexit\n");
        assertThat(result.code()).isZero();
        assertThat(result.stdout()).contains("== majo chat");
        assertThat(result.stdout()).contains("you> 1+2");
        assertThat(result.stdout()).contains("you> and now 3+4");
        long agentReplies = result.stdout().lines().filter(line -> line.startsWith("agent>")).count();
        assertThat(agentReplies).isEqualTo(2);
        assertThat(result.stdout()).contains("calculated: 3");
    }

    @Test
    void chatRejectsAnExtraTaskArgument() {
        Result result = runWithInput(List.of("chat", "surplus-task"), "exit\n");
        assertThat(result.code()).isEqualTo(2);
        assertThat(result.stderr()).contains("chat takes no task argument");
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
    void pluginFlagRequiresNameEqualsPath() {
        Result missingValue = run("--plugin");
        assertThat(missingValue.code()).isEqualTo(2);
        assertThat(missingValue.stderr()).contains("missing value for --plugin");

        Result malformed = run("--plugin", "barejar", "x");
        assertThat(malformed.code()).isEqualTo(2);
        assertThat(malformed.stderr()).contains("--plugin expects name=path");

        Result badJarPath = run("--plugin", "ext=./no-such-plugin.jar", "x");
        assertThat(badJarPath.code()).isEqualTo(1);
        assertThat(badJarPath.stderr()).contains("ext");
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
