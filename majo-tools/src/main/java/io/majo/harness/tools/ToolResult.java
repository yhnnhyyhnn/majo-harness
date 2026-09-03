package io.majo.harness.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The outcome of one tool execution. Either {@code content} (on success) or
 * {@code error} (on failure) carries the model-visible payload; {@code ok}
 * distinguishes them. {@code data} is optional structured metadata that is
 * not part of the model-visible text (exit codes, file paths, structured hit
 * lists…) — consumers like a chat UI render it as a card while the agent
 * keeps reading plain text. May be {@code null}; never carries secrets.
 */
public record ToolResult(boolean ok, String content, String error, Map<String, Object> data) {

    public ToolResult {
        if (data != null) {
            Map<String, Object> copy = new LinkedHashMap<>(data);
            data = Collections.unmodifiableMap(copy);
        }
    }

    public static ToolResult ok(String content) {
        return new ToolResult(true, content, null, null);
    }

    public static ToolResult ok(String content, Map<String, Object> data) {
        return new ToolResult(true, content, null, data);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, null, message, null);
    }

    public static ToolResult error(String message, Map<String, Object> data) {
        return new ToolResult(false, null, message, data);
    }

    /** The model-visible text: content on success, the error otherwise. */
    public String visibleText() {
        return ok ? content : error;
    }
}
