package io.majo.harness.llm;

import java.util.function.Consumer;

/**
 * Streaming capability of a {@link ChatModel}: providers that can emit text
 * tokens as they arrive implement this seam. Implementations push text deltas
 * through {@code onText} while producing the same final {@link ChatResponse}
 * (text and/or assembled tool calls) that {@link ChatModel#complete} would
 * return. Non-streaming providers stay plain {@link ChatModel}; the service
 * falls back to a single burst and never pretends otherwise.
 */
public interface StreamingChatModel {

    /**
     * Completes {@code request} while streaming text deltas to {@code onText},
     * returning the final assembled response.
     */
    ChatResponse completeStream(ChatRequest request, Consumer<String> onText);
}
