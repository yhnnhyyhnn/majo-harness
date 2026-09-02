package io.majo.harness.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.majo.harness.llm.ChatMessage;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.ModelException;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolSpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Wire tests against a local JDK HTTP stub — no network, no key. The stub
 * speaks the OpenAI {@code /chat/completions} shape and verifies the model's
 * request mapping (messages, tools, auth) and response mapping (tool calls,
 * text), plus loud HTTP-error handling.
 */
class OpenAiChatModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final List<JsonNode> lastBodies = new ArrayList<>();
    private String scriptErrorStatus;

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** Responds per a per-request {@code stub} function returning the JSON body. */
    private void start(Stub stub) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBodies.add(MAPPER.readTree(body));
            int status = scriptErrorStatus != null ? Integer.parseInt(scriptErrorStatus) : 200;
            byte[] payload = stub.respond(MAPPER.readTree(body)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface Stub {
        String respond(JsonNode request);
    }

    private static Stub toolTurnStub() {
        return request -> {
            boolean hasToolResult = false;
            String toolContent = null;
            for (JsonNode message : request.path("messages")) {
                if ("tool".equals(message.path("role").asText())) {
                    hasToolResult = true;
                    toolContent = message.path("content").asText();
                }
            }
            if (hasToolResult) {
                return """
                        {"choices":[{"message":{"role":"assistant","content":"calculated: %s"}}]}
                        """.formatted(toolContent);
            }
            return """
                    {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                      {"id":"c1","type":"function","function":{"name":"calc","arguments":"{\\"expression\\":\\"1+2\\"}"}}
                    ]}}]}
                    """;
        };
    }

    @Test
    void completesToolTurnOverTheWire() throws IOException {
        start(toolTurnStub());
        OpenAiChatModel model = new OpenAiChatModel(Map.of(
                "baseUrl", baseUrl(),
                "model", "demo-local"));

        ChatRequest first = new ChatRequest(List.of(ChatMessage.user("1+2")),
                List.of(ToolSpec.of("calc", "evaluates an expression")), null);
        ChatResponse roundOne = model.complete(first);
        assertThat(roundOne.isToolRound()).isTrue();
        assertThat(roundOne.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("c1");
            assertThat(call.name()).isEqualTo("calc");
            assertThat(call.arguments()).contains("1+2");
        });

        // the harness logs the assistant round before executing tools, so the
        // second request replays it plus the tool result
        ToolCall call = roundOne.toolCalls().get(0);
        ChatRequest second = new ChatRequest(List.of(
                ChatMessage.user("1+2"),
                ChatMessage.assistant(null, List.of(call)),
                ChatMessage.toolResult(call.id(), "3")), List.of(), null);
        ChatResponse roundTwo = model.complete(second);
        assertThat(roundTwo.isToolRound()).isFalse();
        assertThat(roundTwo.content()).isEqualTo("calculated: 3");

        // request mapping: model id, tool schema, roles/ids on the wire
        JsonNode wire = lastBodies.get(0);
        assertThat(wire.path("model").asText()).isEqualTo("demo-local");
        assertThat(wire.path("tools").get(0).path("function").path("name").asText()).isEqualTo("calc");
        assertThat(wire.path("tools").get(0).path("function").has("parameters")).isTrue();
        assertThat(wire.path("messages").get(0).path("role").asText()).isEqualTo("user");
        JsonNode replay = lastBodies.get(1);
        assertThat(replay.path("messages").get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(replay.path("messages").get(1).path("tool_calls").get(0).path("id").asText()).isEqualTo("c1");
        assertThat(replay.path("messages").get(2).path("tool_call_id").asText()).isEqualTo("c1");
        assertThat(replay.path("messages").get(2).path("role").asText()).isEqualTo("tool");
    }

    @Test
    void sendsBearerOnlyWhenKeyed() throws IOException {
        start(request -> "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}");
        ChatRequest request = ChatRequest.of(List.of(ChatMessage.user("hi")));

        OpenAiChatModel keyed = new OpenAiChatModel(Map.of(
                "baseUrl", baseUrl(), "model", "m", "apiKey", "secret-abc"));
        assertThat(keyed.complete(request).content()).isEqualTo("ok");
        assertThat(lastAuth.get()).isEqualTo("Bearer secret-abc");

        OpenAiChatModel anonymous = new OpenAiChatModel(Map.of("baseUrl", baseUrl(), "model", "m"));
        assertThat(anonymous.complete(request).content()).isEqualTo("ok");
        assertThat(lastAuth.get()).isNull();
    }

    @Test
    void mapsHttpErrorsLoudly() throws IOException {
        scriptErrorStatus = "401";
        start(request -> "{\"error\":{\"message\":\"bad key\"}}");
        OpenAiChatModel model = new OpenAiChatModel(Map.of("baseUrl", baseUrl(), "model", "m"));
        assertThatThrownBy(() -> model.complete(ChatRequest.of(List.of(ChatMessage.user("hi")))))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("401");
    }

    @Test
    void configValidationFailsLoud() {
        assertThatThrownBy(() -> new OpenAiChatModel(Map.of("model", "m")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
        assertThatThrownBy(() -> new OpenAiChatModel(Map.of("baseUrl", "http://localhost/v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }
}
