package io.majo.harness.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic demo provider (model name {@code mock}): it never reaches a
 * network, so headless runs and tests are reproducible before a real provider
 * lands.
 *
 * <ul>
 *   <li>no tool result in history — requests one tool call. Cue prefixes pick
 *       the tool when the request offers it ({@code file <path>} →
 *       {@code read_file}, {@code shell <script>} → {@code run_shell},
 *       {@code command <argv…>} → {@code run_command}, {@code search <q>} →
 *       {@code web_search}); any other text requests {@code calc} with the
 *       user message as the expression (the classic demo path, kept even when
 *       no tool is offered so existing tests/history stay stable);</li>
 *   <li>history already carries a tool result — answers with
 *       {@code "calculated: <last tool result>"} (or a tool-flavoured prefix
 *       when the requesting call can be identified).</li>
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
            return ChatResponse.text(prefixFor(request) + lastTool.content());
        }
        String userText = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.role() == ChatRole.USER && message.content() != null) {
                userText = message.content();
                break;
            }
        }
        if (userText == null) {
            return ChatResponse.text("no input");
        }
        List<String> offered = new ArrayList<>();
        for (ToolSpec spec : request.tools()) {
            offered.add(spec.name());
        }
        ToolCall call = cueCall(userText, offered);
        if (call == null) {
            return ChatResponse.text("(mock) nothing to compute: \"" + userText + "\"");
        }
        return ChatResponse.toolCalls(List.of(call));
    }

    /** Maps a cue-prefixed request to a tool call when that tool is offered. */
    private static ToolCall cueCall(String userText, List<String> offered) {
        String text = userText.trim();
        try {
            if (text.startsWith("file ") && offered.contains("read_file")) {
                return ToolCall.of("read_file",
                        MAPPER.writeValueAsString(Map.of("path", text.substring(5).trim())));
            }
            if (text.startsWith("shell ") && offered.contains("run_shell")) {
                return ToolCall.of("run_shell",
                        MAPPER.writeValueAsString(Map.of("script", text.substring(6).trim())));
            }
            if (text.startsWith("command ") && offered.contains("run_command")) {
                String[] argv = text.substring(8).trim().split("\\s+");
                return ToolCall.of("run_command",
                        MAPPER.writeValueAsString(Map.of("argv", List.of(argv))));
            }
            if (text.startsWith("search ") && offered.contains("web_search")) {
                return ToolCall.of("web_search",
                        MAPPER.writeValueAsString(Map.of("query", text.substring(7).trim())));
            }
        } catch (Exception e) {
            throw new ModelException("cannot encode tool arguments", e);
        }
        if (offered.contains(TOOL_NAME) || offered.isEmpty()) {
            try {
                return ToolCall.of(TOOL_NAME,
                        MAPPER.writeValueAsString(Map.of("expression", userText)));
            } catch (Exception e) {
                throw new ModelException("cannot encode tool arguments", e);
            }
        }
        return null;
    }

    /** "calculated: " (classic), or a tool-flavoured prefix when identifiable. */
    private static String prefixFor(ChatRequest request) {
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            ChatMessage message = request.messages().get(i);
            if (message.role() == ChatRole.ASSISTANT && message.toolCalls() != null) {
                for (ToolCall call : message.toolCalls()) {
                    switch (call.name()) {
                        case "read_file":
                            return "file content: ";
                        case "run_shell":
                            return "shell output: ";
                        case "run_command":
                            return "command output: ";
                        case "web_search":
                            return "search: ";
                        default:
                            return "calculated: ";
                    }
                }
            }
        }
        return "calculated: ";
    }
}
