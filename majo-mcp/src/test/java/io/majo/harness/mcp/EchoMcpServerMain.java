package io.majo.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Minimal MCP server for tests (newline-delimited JSON-RPC over stdio):
 * one tool {@code echo} whose {@code message} argument comes back as
 * {@code echo: <message>} — with {@code message: "fail"} answering
 * {@code isError} instead. Exits when stdin closes.
 */
public final class EchoMcpServerMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode message = MAPPER.readTree(line);
            if (!message.has("method") || !message.hasNonNull("id")) {
                continue; // notifications (initialized) need no reply
            }
            System.out.println(MAPPER.writeValueAsString(respond(message)));
        }
    }

    private static ObjectNode respond(JsonNode message) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", message.get("id"));
        switch (message.path("method").asText()) {
            case "initialize" -> {
                ObjectNode result = response.putObject("result");
                result.put("protocolVersion", "2024-11-05");
                result.set("capabilities", MAPPER.createObjectNode()
                        .set("tools", MAPPER.createObjectNode()));
                ObjectNode info = result.putObject("serverInfo");
                info.put("name", "echo");
                info.put("version", "0.0.1");
            }
            case "tools/list" -> {
                ObjectNode tool = response.putObject("result")
                        .putArray("tools").addObject();
                tool.put("name", "echo");
                tool.put("description", "echoes the message back");
                ObjectNode schema = tool.putObject("inputSchema");
                schema.put("type", "object");
                ObjectNode properties = schema.putObject("properties");
                properties.putObject("message").put("type", "string");
                schema.putArray("required").add("message");
            }
            case "tools/call" -> {
                String text = message.path("params").path("arguments")
                        .path("message").asText("");
                ObjectNode result = response.putObject("result");
                ObjectNode block = result.putArray("content").addObject();
                block.put("type", "text");
                if ("fail".equals(text)) {
                    block.put("text", "no dice");
                    result.put("isError", true);
                } else {
                    block.put("text", "echo: " + text);
                    if ("exit".equals(text)) {
                        // die right after answering: the next call must reconnect
                        try {
                            System.out.println(MAPPER.writeValueAsString(response));
                        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                            throw new IllegalStateException(e);
                        }
                        System.out.flush();
                        System.exit(0);
                    }
                }
            }
            case "ping" -> response.putObject("result");
            default -> {
                ObjectNode error = response.putObject("error");
                error.put("code", -32601);
                error.put("message", "method not found: " + message.path("method").asText());
            }
        }
        return response;
    }
}
