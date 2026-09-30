package io.majo.harness.llm;

/**
 * Token metering for one model completion (dsh {@code TokenUsage} parity):
 * prompt and completion counts, plus the provider's cache accounting when it
 * reports it (OpenAI {@code prompt_tokens_details.cached_tokens} /
 * DeepSeek {@code cache_read_input_tokens} and
 * {@code cache_creation_input_tokens} ride the same slot). {@code null} cache
 * components mean "not reported"; a response without usage carries
 * {@code null} overall.
 */
public record TokenUsage(long inputTokens, long outputTokens,
        Long cacheReadTokens, Long cacheWriteTokens) {

    public TokenUsage(long inputTokens, long outputTokens) {
        this(inputTokens, outputTokens, null, null);
    }

    public static TokenUsage of(long inputTokens, long outputTokens,
            Long cacheReadTokens, Long cacheWriteTokens) {
        return new TokenUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens);
    }
}
