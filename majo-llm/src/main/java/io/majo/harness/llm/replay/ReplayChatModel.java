package io.majo.harness.llm.replay;

import io.majo.harness.llm.ChatModel;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.ModelException;
import io.majo.harness.llm.StreamingChatModel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Replays an {@link LlmTranscript} fixture (recorded by
 * {@link RecordingChatModel}) in call order. Completions are verified against
 * the recorded request: when the harness derives a different model request
 * than the one that produced the recording, replay fails loudly with the
 * completion index — which is exactly the drift a regression test should
 * catch, not paper over.
 *
 * <p>{@link #completeStream} replays the recorded chunk sequence before
 * returning, so streaming consumers observe the original token rhythm.
 */
public final class ReplayChatModel implements ChatModel, StreamingChatModel {

    private final List<LlmTranscript> transcripts;
    private int cursor;

    public ReplayChatModel(Path fixture) throws IOException {
        this(LlmTranscript.read(fixture));
    }

    public ReplayChatModel(List<LlmTranscript> transcripts) {
        if (transcripts.isEmpty()) {
            throw new IllegalArgumentException("replay fixture carries no completions");
        }
        this.transcripts = List.copyOf(transcripts);
    }

    /** Completions still unread — tests assert exhaustion after a replayed run. */
    public int remaining() {
        return transcripts.size() - cursor;
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        LlmTranscript transcript = next(request);
        return transcript.response();
    }

    @Override
    public ChatResponse completeStream(ChatRequest request, Consumer<String> onText) {
        LlmTranscript transcript = next(request);
        for (String chunk : transcript.chunks()) {
            onText.accept(chunk);
        }
        return transcript.response();
    }

    private LlmTranscript next(ChatRequest request) {
        if (cursor >= transcripts.size()) {
            throw new ModelException("replay exhausted: no recording for completion "
                    + cursor + " (fixture holds " + transcripts.size() + ")");
        }
        LlmTranscript transcript = transcripts.get(cursor);
        if (!transcript.request().equals(request)) {
            throw new ModelException("replay drift at completion " + cursor
                    + ": the derived model request differs from the recording —"
                    + " model-visible history changed. Expected " + transcript.request()
                    + " but got " + request);
        }
        cursor++;
        return transcript;
    }
}
