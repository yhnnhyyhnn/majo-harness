package io.majo.harness.hooks;

/**
 * Matcher semantics shared with the dsh hook protocol: absent/empty/{@code *}
 * match all; a Claude-dialect pattern that is purely word chars and pipes is
 * a literal exact-match alternation ({@code Read|Write}), any other pattern is
 * an unanchored regular expression. Invalid regexes match nothing instead of
 * throwing (config parsing surfaces them loudly via {@link #diagnostic}).
 */
public final class HookMatcher {

    private static final java.util.regex.Pattern CLAUDE_LITERAL =
            java.util.regex.Pattern.compile("[A-Za-z0-9_|]+");

    private HookMatcher() {}

    /** True for the match-all sentinels ({@code null}, blank, {@code *}). */
    public static boolean isMatchAll(String matcher) {
        return matcher == null || matcher.isBlank() || "*".equals(matcher);
    }

    /**
     * A config-level check: {@code null} for a valid pattern, otherwise a
     * stable diagnostic (only invalid regexes are rejected).
     */
    public static String diagnostic(String matcher) {
        if (isMatchAll(matcher) || CLAUDE_LITERAL.matcher(matcher).matches()) {
            return null;
        }
        try {
            java.util.regex.Pattern.compile(matcher);
            return null;
        } catch (java.util.regex.PatternSyntaxException e) {
            return "invalid regex matcher \"" + matcher + "\": " + e.getMessage();
        }
    }

    /** Whether the pattern selects {@code query} (never throws). */
    public static boolean matches(String matcher, String query) {
        if (isMatchAll(matcher)) {
            return true;
        }
        if (CLAUDE_LITERAL.matcher(matcher).matches()) {
            for (String alternative : matcher.split("\\|")) {
                if (alternative.equals(query)) {
                    return true;
                }
            }
            return false;
        }
        try {
            return java.util.regex.Pattern.compile(matcher).matcher(query).find();
        } catch (java.util.regex.PatternSyntaxException e) {
            return false;
        }
    }
}
