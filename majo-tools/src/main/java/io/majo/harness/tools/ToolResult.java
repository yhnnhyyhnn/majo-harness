package io.majo.harness.tools;

/**
 * The outcome of one tool execution. Either {@code content} (on success) or
 * {@code error} (on failure) carries the model-visible payload; {@code ok}
 * distinguishes them.
 */
public record ToolResult(boolean ok, String content, String error) {

    public static ToolResult ok(String content) {
        return new ToolResult(true, content, null);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, null, message);
    }

    /** The model-visible text: content on success, the error otherwise. */
    public String visibleText() {
        return ok ? content : error;
    }
}
