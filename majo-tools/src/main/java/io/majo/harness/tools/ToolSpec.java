package io.majo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A tool's model-visible contract: name, description, and a JSON Schema
 * {@code parameters} node that joins prompt assembly for the LLM request.
 */
public record ToolSpec(String name, String description, JsonNode parameters) {

    /** A spec with an open (unconstrained) parameter schema. */
    public static ToolSpec of(String name, String description) {
        return new ToolSpec(name, description, new ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance));
    }
}
