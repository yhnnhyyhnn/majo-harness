package io.majo.harness.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.llm.ChatMessage;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.ChatRole;
import io.majo.harness.llm.ModelException;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolSpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A {@code ChatModel} speaking the OpenAI {@code /chat/completions} wire
 * protocol, so any OpenAI-compatible endpoint works without harness changes:
 * LM Studio, Ollama, vLLM, One-API/other gateways, or a remote vendor keyed by
 * {@code apiKey}. Local endpoints need no key at all.
 *
 * <p>Config (all values read from the provider plugin's config map):
 * <ul>
 *   <li>{@code baseUrl} — required, e.g. {@code http://localhost:1234/v1}
 *       (an explicit {@code /chat/completions} suffix is also accepted);</li>
 *   <li>{@code model} — required, the model id sent on the wire;</li>
 *   <li>{@code name} — optional registry key under which this model is
 *       registered on {@code ctx.llm} (defaults to {@code model});</li>
 *   <li>{@code apiKey} — optional; sent as {@code Authorization: Bearer}; an
 *       entry of the form {@code ${ENV_VAR}} reads the variable at runtime, so
 *       secrets never need to live in a profile file (missing variables fail
 *       loudly);</li>
 *   <li>{@code headers} — optional extra header map for gateway auth schemes
 *       (same {@code ${ENV_VAR}} expansion);</li>
 *   <li>{@code temperature} / {@code maxTokens} — optional sampling knobs;</li>
 *   <li>{@code timeoutSeconds} — optional HTTP timeout (default 60).</li>
 * </ul>
 */
public final class OpenAiChatModel implements io.majo.harness.llm.ChatModel, io.majo.harness.llm.StreamingChatModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final Map<String, String> extraHeaders;
    private final Double temperature;
    private final Integer maxTokens;

    OpenAiChatModel(Object config) {
        Map<?, ?> map = config instanceof Map<?, ?> m ? m : Map.of();
        String baseUrl = required(map, "baseUrl", "OpenAiChatModel");
        this.model = required(map, "model", "OpenAiChatModel");
        this.apiKey = expand(stringOrNull(map, "apiKey"), "apiKey");
        this.extraHeaders = headers(map.get("headers"));
        this.temperature = numberOrNull(map, "temperature");
        this.maxTokens = integerOrNull(map, "maxTokens");
        long timeout = map.get("timeoutSeconds") instanceof Number n ? n.longValue() : 60L;
        this.endpoint = endpoint(baseUrl);
        this.requestTimeout = Duration.ofSeconds(timeout);
        this.client = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
    }

    private final Duration requestTimeout;

    private static String required(Map<?, ?> map, String key, String where) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException(where + ": config requires \"" + key + "\"");
        }
        return String.valueOf(value);
    }

    private static String stringOrNull(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Double numberOrNull(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Integer integerOrNull(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> headers(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        java.util.HashMap<String, String> result = new java.util.HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()),
                    expand(String.valueOf(entry.getValue()), "headers." + entry.getKey()));
        }
        return result;
    }

    /**
     * Expands {@code ${ENV_VAR}} references from the environment; a variable
     * that is unset fails loudly rather than sending a placeholder.
     */
    private static String expand(String value, String where) {
        if (value == null || !value.startsWith("${") || !value.endsWith("}")) {
            return value;
        }
        String name = value.substring(2, value.length() - 1);
        String resolved = System.getenv(name);
        if (resolved == null) {
            throw new IllegalArgumentException(where + " references env " + name + " which is not set");
        }
        return resolved;
    }

    private static URI endpoint(String baseUrl) {
        String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (!url.endsWith("/chat/completions")) {
            url = url + "/chat/completions";
        }
        return URI.create(url);
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        return completeInternal(request, null);
    }

    @Override
    public ChatResponse completeStream(ChatRequest request, java.util.function.Consumer<String> onText) {
        return completeInternal(request, onText == null ? ignored -> {} : onText);
    }

    private ChatResponse completeInternal(ChatRequest request, java.util.function.Consumer<String> onText) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(buildBody(request, onText != null))));
            if (apiKey != null) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            extraHeaders.forEach(builder::header);
            if (onText == null) {
                HttpResponse<String> response = client.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString());
                return parse(response);
            }
            HttpResponse<InputStream> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModelException("provider returned HTTP " + response.statusCode() + ": "
                        + snippet(readBody(response.body())));
            }
            return readStream(response.body(), onText);
        } catch (ModelException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("provider request to " + endpoint + " failed: " + e, e);
        }
    }

    private ObjectNode buildBody(ChatRequest request, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.set("messages", messages(request.messages()));
        if (!request.tools().isEmpty()) {
            body.set("tools", tools(request.tools()));
        }
        if (stream) {
            body.put("stream", true);
        }
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        return body;
    }

    /**
     * Reads an SSE stream: text deltas flow to {@code onText}; tool-call
     * deltas accumulate by index into the final {@link ChatResponse}.
     */
    private ChatResponse readStream(InputStream body, java.util.function.Consumer<String> onText)
            throws IOException {
        StringBuilder text = new StringBuilder();
        java.util.TreeMap<Integer, MutableCall> calls = new java.util.TreeMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).strip();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode root = MAPPER.readTree(payload);
                JsonNode error = root.path("error");
                if (!error.isMissingNode()) {
                    throw new ModelException("provider stream error: " + error.path("message").asText());
                }
                JsonNode choice = root.path("choices").path(0);
                if (choice.isMissingNode()) {
                    continue;
                }
                JsonNode delta = choice.path("delta");
                if (delta.hasNonNull("content")) {
                    String token = delta.get("content").asText();
                    text.append(token);
                    onText.accept(token);
                }
                for (JsonNode call : delta.path("tool_calls")) {
                    int index = call.path("index").asInt(0);
                    MutableCall entry = calls.computeIfAbsent(index, ignored -> new MutableCall());
                    JsonNode function = call.path("function");
                    if (call.hasNonNull("id") && entry.id == null) {
                        entry.id = call.get("id").asText();
                    }
                    if (function.hasNonNull("name") && entry.name == null) {
                        entry.name = function.get("name").asText();
                    }
                    if (function.hasNonNull("arguments")) {
                        entry.arguments.append(function.get("arguments").asText());
                    }
                }
            }
        }
        String content = text.length() == 0 ? null : text.toString();
        if (calls.isEmpty()) {
            return ChatResponse.text(content);
        }
        java.util.List<ToolCall> toolCalls = new java.util.ArrayList<>();
        for (MutableCall call : calls.values()) {
            toolCalls.add(new ToolCall(
                    call.id != null ? call.id : UUID.randomUUID().toString(),
                    call.name == null ? "" : call.name,
                    call.arguments.toString()));
        }
        return new ChatResponse(content, java.util.List.copyOf(toolCalls));
    }

    private static String readBody(InputStream stream) throws IOException {
        try (InputStream input = stream) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class MutableCall {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private static ArrayNode messages(List<ChatMessage> messages) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ChatMessage message : messages) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("role", role(message.role()));
            if (message.content() != null) {
                node.put("content", message.content());
            } else {
                node.putNull("content");
            }
            if (message.role() == ChatRole.ASSISTANT && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments());
                }
            }
            if (message.role() == ChatRole.TOOL) {
                node.put("tool_call_id", message.toolCallId());
            }
            array.add(node);
        }
        return array;
    }

    private static String role(ChatRole role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }

    private static ArrayNode tools(List<ToolSpec> tools) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ToolSpec spec : tools) {
            ObjectNode wrapper = array.addObject();
            wrapper.put("type", "function");
            ObjectNode function = wrapper.putObject("function");
            function.put("name", spec.name());
            if (spec.description() != null) {
                function.put("description", spec.description());
            }
            function.set("parameters", spec.parameters() == null ? MAPPER.createObjectNode() : spec.parameters());
        }
        return array;
    }

    private ChatResponse parse(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ModelException("provider returned HTTP " + response.statusCode() + ": " + snippet(body));
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode choice = root.path("choices").path(0);
            if (choice.isMissingNode()) {
                throw new ModelException("provider response has no choices: " + snippet(body));
            }
            JsonNode message = choice.path("message");
            String content = message.has("content") && !message.get("content").isNull()
                    ? message.get("content").asText()
                    : null;
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                JsonNode function = call.path("function");
                String id = call.hasNonNull("id") ? call.get("id").asText() : UUID.randomUUID().toString();
                calls.add(new ToolCall(id, function.path("name").asText(), function.path("arguments").asText()));
            }
            return new ChatResponse(content, List.copyOf(calls));
        } catch (IOException e) {
            throw new ModelException("cannot parse provider response: " + snippet(body), e);
        }
    }

    private static String snippet(String body) {
        String trimmed = body.strip();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
    }
}
