package io.majo.harness.llm;

/**
 * The LLM adapter seam (Service Definition): a model provider implements this
 * interface and registers itself on {@link LLMService}; the agent loop only
 * ever talks to the service. Providers must be pure and stateless across
 * calls — configuration and credentials belong to the providing plugin.
 */
public interface ChatModel {

    ChatResponse complete(ChatRequest request);
}
