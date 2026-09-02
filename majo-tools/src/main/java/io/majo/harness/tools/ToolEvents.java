package io.majo.harness.tools;

/**
 * The tools extension points: waterfall events on the shared event bus.
 *
 * <p>Listeners registered on {@link #PRE_EXECUTE} may rewrite the call
 * (mutate {@code args[0]} and call {@code next()}) or reject it outright by
 * returning a {@link ToolResult} without calling {@code next()} — the rejection
 * short-circuits execution and becomes the result. {@link #POST_EXECUTE}
 * listeners observe or transform the produced result the same way. Waterfall
 * listeners must call {@code next()} to delegate.
 */
public final class ToolEvents {

    /** Waterfall {@code (ToolCall call, Tool tool)} around tool execution. */
    public static final String PRE_EXECUTE = "tools/pre-execute";
    /** Waterfall {@code (ToolCall call, ToolResult result)} after execution. */
    public static final String POST_EXECUTE = "tools/post-execute";

    private ToolEvents() {}
}
