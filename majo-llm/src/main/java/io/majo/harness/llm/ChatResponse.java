package io.majo.harness.llm;

import io.majo.harness.tools.ToolCall;
import java.util.List;

/**
 * One model completion response. A round that requests tools carries
 * {@code toolCalls} (and typically no text); otherwise it is final text.
 * {@code usage} carries the provider's token metering when it reports it
 * (absent for providers that do not).
 */
public record ChatResponse(String content, List<ToolCall> toolCalls, TokenUsage usage) {

    /** Back-compat shape: no usage reported. */
    public ChatResponse(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, null);
    }

    public static ChatResponse text(String content) {
        return new ChatResponse(content, List.of());
    }

    public static ChatResponse text(String content, TokenUsage usage) {
        return new ChatResponse(content, List.of(), usage);
    }

    public static ChatResponse toolCalls(List<ToolCall> toolCalls) {
        return new ChatResponse(null, List.copyOf(toolCalls));
    }

    public static ChatResponse toolCalls(List<ToolCall> toolCalls, TokenUsage usage) {
        return new ChatResponse(null, List.copyOf(toolCalls), usage);
    }

    /** Whether this round asked the harness to execute tools. */
    @com.fasterxml.jackson.annotation.JsonIgnore // derived view, not a wire property
    public boolean isToolRound() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
