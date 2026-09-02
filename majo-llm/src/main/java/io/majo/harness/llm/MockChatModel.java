package io.majo.harness.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.majo.harness.tools.ToolCall;
import java.util.List;

/**
 * Deterministic demo provider (model name {@code mock}): it never reaches a
 * network, so headless runs and tests are reproducible before a real provider
 * lands.
 *
 * <ul>
 *   <li>no tool result in history — requests one {@code calc} call whose
 *       {@code expression} argument is the last user message;</li>
 *   <li>history already carries a tool result — answers with
 *       {@code "calculated: <last tool result>"}.</li>
 * </ul>
 */
public final class MockChatModel implements ChatModel, StreamingChatModel {

    public static final String MODEL_NAME = "mock";
    public static final String TOOL_NAME = "calc";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ChatResponse complete(ChatRequest request) {
        return decide(request);
    }

    @Override
    public ChatResponse completeStream(ChatRequest request, java.util.function.Consumer<String> onText) {
        ChatResponse response = decide(request);
        if (response.content() != null) {
            onText.accept(response.content());
        }
        return response;
    }

    private static ChatResponse decide(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        ChatMessage lastTool = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.role() == ChatRole.TOOL) {
                lastTool = message;
                break;
            }
        }
        if (lastTool != null) {
            return ChatResponse.text("calculated: " + lastTool.content());
        }
        String expression = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.role() == ChatRole.USER && message.content() != null) {
                expression = message.content();
                break;
            }
        }
        if (expression == null) {
            return ChatResponse.text("no input");
        }
        try {
            String arguments = MAPPER.writeValueAsString(java.util.Map.of("expression", expression));
            return ChatResponse.toolCalls(List.of(ToolCall.of(TOOL_NAME, arguments)));
        } catch (Exception e) {
            throw new ModelException("cannot encode tool arguments", e);
        }
    }
}
