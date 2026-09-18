package io.majo.harness.tools;

/**
 * A tool contributed by a plugin: declares its {@link ToolSpec} and executes
 * model-requested {@link ToolCall calls}. Tools must not have owned mutable
 * state — registrations and their side effects belong to the plugin fiber.
 */
public interface Tool {

    /**
     * Spec-description marker that exempts a gated tool from approval (the
     * tool author takes responsibility — e.g. a trigger for a workflow whose
     * definition declares {@code allowModelTrigger: true}).
     */
    String ALLOW_MODEL_TRIGGER_TAG = "[allow-model-trigger]";

    ToolSpec spec();

    ToolResult execute(ToolCall call);
}
