package io.majo.harness.tools;

import java.util.UUID;

/**
 * A tool invocation requested by the model: id, tool name, and arguments as a
 * JSON string (OpenAI-style). Branded ids keep tool results referable across
 * the session log and the model protocol.
 */
public record ToolCall(String id, String name, String arguments) {

    /** Creates a call with a fresh id. */
    public static ToolCall of(String name, String arguments) {
        return new ToolCall(UUID.randomUUID().toString(), name, arguments);
    }
}
