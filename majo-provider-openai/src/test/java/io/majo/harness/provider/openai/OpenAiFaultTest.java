package io.majo.harness.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatMessage;
import io.majo.harness.llm.ChatRole;
import io.majo.harness.llm.ModelException;
import io.majo.harness.llm.fault.FaultLlmServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Wire-level failure contract, driven by {@link FaultLlmServer} (the
 * llm-mock-server analog): mid-stream disconnects, 429/5xx, malformed SSE
 * chunks, in-stream error objects, and non-JSON bodies all fail loudly as
 * {@link ModelException} — never as a silently truncated "successful"
 * response, and never with a hidden retry loop inside the adapter.
 */
class OpenAiFaultTest {

    private FaultLlmServer server;

    private OpenAiChatModel model() {
        return new OpenAiChatModel(Map.of("baseUrl", server.baseUrl(), "model", "fault-model"));
    }

    private static ChatRequest request() {
        return new ChatRequest(List.of(ChatMessage.user("hi")), List.of(), null);
    }

    private static String delta(String text) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}\n\n";
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void midStreamDisconnectFailsLoudInsteadOfReturningPartialText() throws IOException {
        server = FaultLlmServer.start();
        server.enqueue(FaultLlmServer.truncatedBody(delta("hello "), 500));
        List<String> chunks = new ArrayList<>();

        assertThatThrownBy(() -> model().completeStream(request(), chunks::add))
                .isInstanceOf(ModelException.class);
        // whatever deltas slipped through before the cut, the call failed
        assertThat(chunks).containsExactly("hello ");

        assertThatThrownBy(() -> model().complete(request()))
                .isInstanceOf(ModelException.class);
    }

    @Test
    void http429SurfacesLoudlyWithoutAnAdapterRetryLoop() throws IOException {
        server = FaultLlmServer.start();
        server.enqueue(FaultLlmServer.response(429, Map.of("Retry-After", "7"),
                "{\"error\":\"rate limited\"}"));

        assertThatThrownBy(() -> model().complete(request()))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("HTTP 429")
                .hasMessageContaining("rate limited");
        assertThat(server.served()).isEqualTo(1);
    }

    @Test
    void http500SurfacesLoudly() throws IOException {
        server = FaultLlmServer.start();
        server.enqueue(FaultLlmServer.response(500, Map.of(), "backend exploded"));

        assertThatThrownBy(() -> model().complete(request()))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("backend exploded");
    }

    @Test
    void malformedSseChunkFailsLoud() throws IOException {
        server = FaultLlmServer.start();
        server.enqueue(FaultLlmServer.response(200, Map.of(),
                "data: {not json\n\ndata: [DONE]\n\n"));

        assertThatThrownBy(() -> model().completeStream(request(), ignored -> {}))
                .isInstanceOf(ModelException.class);
    }

    @Test
    void inStreamErrorObjectFailsLoud() throws IOException {
        server = FaultLlmServer.start();
        server.enqueue(FaultLlmServer.response(200, Map.of(),
                "data: {\"error\":{\"message\":\"overloaded\"}}\n\ndata: [DONE]\n\n"));

        assertThatThrownBy(() -> model().completeStream(request(), ignored -> {}))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("overloaded");
    }

    @Test
    void nonJsonSuccessBodyFailsLoudOnPlainComplete() throws IOException {
        server = FaultLlmServer.start();
        server.enqueue(FaultLlmServer.response(200, Map.of(), "this is not json"));

        assertThatThrownBy(() -> model().complete(request()))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("cannot parse provider response");
    }

    @Test
    void stalledResponseFailsOnTheClientTimeout() throws IOException {
        server = FaultLlmServer.start();
        server.enqueue(FaultLlmServer.stall(5_000));
        OpenAiChatModel impatient = new OpenAiChatModel(Map.of(
                "baseUrl", server.baseUrl(), "model", "fault-model", "timeoutSeconds", 1));

        assertThatThrownBy(() -> impatient.complete(request()))
                .isInstanceOf(ModelException.class);
        // the client gave up; the server saw exactly one request
        assertThat(server.served()).isEqualTo(1);
    }
}
