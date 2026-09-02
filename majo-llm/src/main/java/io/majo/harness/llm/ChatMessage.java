package io.majo.harness.llm;

import io.majo.harness.tools.ToolCall;
import java.util.List;

/**
 * One model-protocol message. Content and tool calls are mutually readable per
 * role: assistant rounds may carry both text and tool calls, tool results
 * carry {@code toolCallId} plus content.
 */
public record ChatMessage(ChatRole role, String content, String toolCallId, List<ToolCall> toolCalls) {

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(ChatRole.ASSISTANT, content, null,
                toolCalls == null ? null : List.copyOf(toolCalls));
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage(ChatRole.TOOL, content, toolCallId, null);
    }
}
