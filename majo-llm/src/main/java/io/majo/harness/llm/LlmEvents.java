package io.majo.harness.llm;

/**
 * LLM observability extension points. Plain events fired around every
 * completion so telemetry, replay, and guard plugins can observe requests and
 * responses without importing the agent loop.
 */
public final class LlmEvents {

    /** Fired before a completion with {@code (ChatRequest request, String model)}. */
    public static final String REQUEST = "llm/request";
    /** Fired after a completion with {@code (ChatRequest request, ChatResponse response, String model)}. */
    public static final String RESPONSE = "llm/response";

    private LlmEvents() {}
}
