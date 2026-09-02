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
import java.io.IOException;
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
 *   <li>{@code apiKey} — optional; sent as {@code Authorization: Bearer};</li>
 *   <li>{@code headers} — optional extra header map for gateway auth schemes;</li>
 *   <li>{@code temperature} / {@code maxTokens} — optional sampling knobs;</li>
 *   <li>{@code timeoutSeconds} — optional HTTP timeout (default 60).</li>
 * </ul>
 */
public final class OpenAiChatModel implements io.majo.harness.llm.ChatModel {

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
        this.apiKey = stringOrNull(map, "apiKey");
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
            result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return result;
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
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.set("messages", messages(request.messages()));
        if (!request.tools().isEmpty()) {
            body.set("tools", tools(request.tools()));
        }
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            if (apiKey != null) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            extraHeaders.forEach(builder::header);
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            return parse(response);
        } catch (ModelException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("provider request to " + endpoint + " failed: " + e, e);
        }
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
