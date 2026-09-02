package io.majo.harness.tools;

/**
 * A tool contributed by a plugin: declares its {@link ToolSpec} and executes
 * model-requested {@link ToolCall calls}. Tools must not have owned mutable
 * state — registrations and their side effects belong to the plugin fiber.
 */
public interface Tool {

    ToolSpec spec();

    ToolResult execute(ToolCall call);
}
