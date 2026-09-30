package io.majo.harness.hooks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The config/matcher/codec contracts: hooks.json parsing (command-only, loud
 * on invalid regexes), the dsh matcher dialect (literal pipe alternation vs
 * unanchored regex, match-all sentinels), and outcome decoding (exit-2
 * blocking, permissionDecision, plain-text context, halt requests).
 */
class HooksConfigTest {

    @Test
    void parsesTheClaudeCodeShapeAndSkipsNonCommandHooks() {
        HooksConfig config = HooksConfig.parse("""
                {"hooks": {
                  "PreToolUse": [
                    {"matcher": "Bash",
                     "hooks": [{"type": "command", "command": "./gate.sh", "timeout": 30},
                               {"type": "prompt", "prompt": "ignored"}]},
                    {"hooks": [{"type": "command", "command": "./audit.sh"}]}
                  ],
                  "Stop": [{"matcher": "", "hooks": [{"type": "command", "command": "./stop.sh"}]}]
                }}
                """);
        assertThat(config.events()).containsExactlyInAnyOrder("PreToolUse", "Stop");
        assertThat(config.groups("PreToolUse")).hasSize(2);
        assertThat(config.groups("PreToolUse").get(0).matcher()).isEqualTo("Bash");
        assertThat(config.groups("PreToolUse").get(0).hooks())
                .containsExactly(new HooksConfig.CommandHook("./gate.sh", 30));
        assertThat(config.groups("PreToolUse").get(1).matcher()).isNull();
        assertThat(config.groups("Stop")).hasSize(1);
    }

    @Test
    void invalidRegexMatcherFailsLoudly() {
        assertThatThrownBy(() -> HooksConfig.parse("""
                {"PreToolUse": [{"matcher": "([", "hooks": [{"type": "command", "command": "x"}]}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid regex matcher");
    }

    @Test
    void commandHookWithoutCommandFailsLoudly() {
        assertThatThrownBy(() -> HooksConfig.parse("""
                {"PreToolUse": [{"hooks": [{"type": "command"}]}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command hook requires");
    }

    @Test
    void matcherDialectMatchesLikeTheReference() {
        // match-all sentinels
        assertThat(HookMatcher.matches(null, "Bash")).isTrue();
        assertThat(HookMatcher.matches("", "Bash")).isTrue();
        assertThat(HookMatcher.matches("*", "Bash")).isTrue();
        // word-and-pipe = literal exact alternation
        assertThat(HookMatcher.matches("Bash|Write", "Write")).isTrue();
        assertThat(HookMatcher.matches("Bash|Write", "write")).isFalse();
        assertThat(HookMatcher.matches("Bash|Write", "xWrite")).isFalse();
        // anything else = unanchored regex (patterns with non-word chars)
        assertThat(HookMatcher.matches("^mcp__.*", "mcp__srv__tool")).isTrue();
        assertThat(HookMatcher.matches("web.*", "web_search")).isTrue();
        assertThat(HookMatcher.matches("search$", "web_search")).isTrue();
        assertThat(HookMatcher.matches("web.*", "grep")).isFalse();
        // a pure-word pattern is literal: exact equality only
        assertThat(HookMatcher.matches("web", "web")).isTrue();
        assertThat(HookMatcher.matches("web", "web_search")).isFalse();
        // invalid regex matches nothing
        assertThat(HookMatcher.matches("([", "anything")).isFalse();
    }

    @Test
    void exitTwoBlocksWithStderrAsReason() {
        HookOutput output = HookOutput.of(2, "", "  forbidden path touched  ");
        assertThat(output.blocks()).isTrue();
        assertThat(output.blockReason()).isEqualTo("forbidden path touched");
    }

    @Test
    void permissionDecisionDrivesTheDecision() {
        HookOutput deny = HookOutput.of(0,
                "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\","
                        + "\"permissionDecision\":\"deny\","
                        + "\"permissionDecisionReason\":\"no rm -rf\"}}", "");
        assertThat(deny.blocks()).isTrue();
        assertThat(deny.blockReason()).isEqualTo("no rm -rf");

        HookOutput allow = HookOutput.of(0,
                "{\"hookSpecificOutput\":{\"permissionDecision\":\"allow\"}}", "");
        assertThat(allow.blocks()).isFalse();
        assertThat(allow.allows()).isTrue();

        // a bare top-level decision is invalid per the schemas: ignored
        HookOutput outOfBand = HookOutput.of(0, "{\"decision\":\"deny\"}", "");
        assertThat(outOfBand.blocks()).isFalse();
    }

    @Test
    void haltRequestBlocksAndPlainStdoutBecomesContext() {
        HookOutput halt = HookOutput.of(0,
                "{\"continue\":false,\"stopReason\":\"stop for review\"}", "");
        assertThat(halt.blocks()).isTrue();
        assertThat(halt.blockReason()).isEqualTo("stop for review");

        HookOutput plain = HookOutput.of(0, "remember to lint", "");
        assertThat(plain.blocks()).isFalse();
        assertThat(plain.additionalContext()).isEqualTo("remember to lint");

        // a nonzero non-blocking exit (1): not a block, stderr preserved
        HookOutput warn = HookOutput.of(1, "", "warning only");
        assertThat(warn.blocks()).isFalse();
    }

    @Test
    void unrunnableHookBlocksNothing() {
        HookOutput broken = HookOutput.of(null, "", "cannot start program");
        assertThat(broken.blocks()).isFalse();
        assertThat(broken.exitCode()).isNull();
    }
}
