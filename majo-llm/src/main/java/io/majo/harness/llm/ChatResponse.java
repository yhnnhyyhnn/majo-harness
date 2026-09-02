package io.majo.harness.llm;

import io.majo.harness.tools.ToolCall;
import java.util.List;

/**
 * One model completion response. A round that requests tools carries
 * {@code toolCalls} (and typically no text); otherwise it is final text.
 */
public record ChatResponse(String content, List<ToolCall> toolCalls) {

    public static ChatResponse text(String content) {
        return new ChatResponse(content, List.of());
    }

    public static ChatResponse toolCalls(List<ToolCall> toolCalls) {
        return new ChatResponse(null, List.copyOf(toolCalls));
    }

    /** Whether this round asked the harness to execute tools. */
    public boolean isToolRound() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
