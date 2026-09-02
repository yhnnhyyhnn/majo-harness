package io.majo.harness.llm;

import io.majo.harness.tools.ToolSpec;
import java.util.List;

/**
 * One model completion request: message history plus the tool schemas offered.
 * {@code model} selects a registered model, or {@code null} to use the
 * service's configured default.
 */
public record ChatRequest(List<ChatMessage> messages, List<ToolSpec> tools, String model) {

    public ChatRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static ChatRequest of(List<ChatMessage> messages) {
        return new ChatRequest(messages, List.of(), null);
    }
}
