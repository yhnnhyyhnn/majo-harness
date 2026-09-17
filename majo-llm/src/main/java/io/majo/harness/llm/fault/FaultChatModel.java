package io.majo.harness.llm.fault;

import io.majo.harness.llm.ChatModel;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.ModelException;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Scripted-failure {@link ChatModel} wrapper — the seam-level half of the
 * fault-injection infra (the wire-level half is {@link FaultLlmServer}).
 * Calls delegate to an inner model until the script says otherwise: a scripted
 * call number throws, or (streaming only) emits a few text deltas first and
 * then dies, simulating a mid-stream transport death.
 *
 * <p>Tests use this to pin the failure contract above the model seam without
 * a network: turns must fail loudly, and the session log must never carry a
 * half-committed assistant round.
 */
public final class FaultChatModel implements ChatModel, io.majo.harness.llm.StreamingChatModel {

    private final ChatModel inner;
    /** call number (1-based) → failure to raise instead of delegating. */
    private final TreeMap<Integer, Failure> script = new TreeMap<>();
    private int calls;

    private record Failure(String message, int chunksBeforeDeath) {
    }

    public FaultChatModel(ChatModel inner) {
        this.inner = inner;
    }

    /** The {@code callNumber}-th call (1-based) throws a {@link ModelException}. */
    public FaultChatModel failOnCall(int callNumber, String message) {
        script.put(callNumber, new Failure(message, 0));
        return this;
    }

    /**
     * The {@code callNumber}-th streaming call emits {@code chunksBeforeDeath}
     * text deltas and then throws — a stream killed mid-flight.
     */
    public FaultChatModel failStreamOnCall(int callNumber, int chunksBeforeDeath, String message) {
        script.put(callNumber, new Failure(message, chunksBeforeDeath));
        return this;
    }

    /** Completed calls so far, including failed ones. */
    public int calls() {
        return calls;
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        return dispatch(++calls, request, null, false);
    }

    @Override
    public ChatResponse completeStream(ChatRequest request, Consumer<String> onText) {
        return dispatch(++calls, request, onText == null ? ignored -> {} : onText, true);
    }

    private ChatResponse dispatch(int call, ChatRequest request, Consumer<String> onText, boolean streaming) {
        Failure failure = script.get(call);
        if (failure != null) {
            for (int chunk = 0; chunk < failure.chunksBeforeDeath(); chunk++) {
                onText.accept("chunk-" + (chunk + 1) + " ");
            }
            throw new ModelException("fault-injection call " + call + ": " + failure.message());
        }
        if (!streaming) {
            return inner.complete(request);
        }
        if (inner instanceof io.majo.harness.llm.StreamingChatModel streamingInner) {
            return streamingInner.completeStream(request, onText);
        }
        ChatResponse response = inner.complete(request);
        if (response.content() != null) {
            onText.accept(response.content());
        }
        return response;
    }
}
