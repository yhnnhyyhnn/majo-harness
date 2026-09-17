package io.majo.harness.llm.replay;

import io.majo.harness.llm.ChatModel;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.StreamingChatModel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link ChatModel} wrapper that records every completion (request, streamed
 * chunks, response) into an {@link LlmTranscript} list for later offline
 * replay through {@link ReplayChatModel}. Wrap a real provider during a
 * recorded session, then {@link #writeTo(Path)} and commit the fixture.
 *
 * <p>Streaming is honored when the delegate implements
 * {@link StreamingChatModel}; the observed chunk sequence is what gets
 * recorded, so replay reproduces the same token rhythm.
 */
public final class RecordingChatModel implements ChatModel, StreamingChatModel {

    private final ChatModel delegate;
    private final List<LlmTranscript> recordings = new ArrayList<>();

    public RecordingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        ChatResponse response = delegate.complete(request);
        recordings.add(new LlmTranscript(request, List.of(), response));
        return response;
    }

    @Override
    public ChatResponse completeStream(ChatRequest request, Consumer<String> onText) {
        List<String> chunks = new ArrayList<>();
        Consumer<String> recording = text -> {
            chunks.add(text);
            onText.accept(text);
        };
        ChatResponse response;
        if (delegate instanceof StreamingChatModel streaming) {
            response = streaming.completeStream(request, recording);
        } else {
            response = delegate.complete(request);
            if (response.content() != null) {
                recording.accept(response.content());
            }
        }
        recordings.add(new LlmTranscript(request, List.copyOf(chunks), response));
        return response;
    }

    /** Every completion so far, in call order. */
    public List<LlmTranscript> transcripts() {
        return List.copyOf(recordings);
    }

    /** Persists the recordings as a replayable JSONL fixture. */
    public void writeTo(Path file) throws IOException {
        LlmTranscript.write(file, recordings);
    }
}
