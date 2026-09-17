package io.majo.harness.llm.replay;

import io.majo.harness.llm.ChatMessage;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.MockChatModel;
import io.majo.harness.llm.ModelException;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Record/replay round trip: a scripted provider is recorded (non-streaming
 * tool-call round + streaming text round), persisted as a fixture, then
 * replayed byte-for-byte — including drift detection when the derived request
 * stops matching and loud exhaustion when the script runs dry.
 */
class ReplayRoundTripTest {

    private static ChatRequest request(List<ChatMessage> messages) {
        return new ChatRequest(messages, List.of(ToolSpec.of("calc", "evaluate")), "mock");
    }

    @Test
    void recordsAndReplaysCompletions(@TempDir Path temp) throws IOException {
        ToolCall call = ToolCall.of("calc", "{\"expression\":\"1+2\"}");
        ChatRequest toolRound = request(List.of(
                ChatMessage.system("s"), ChatMessage.user("1+2")));
        ChatRequest finalRound = request(List.of(
                ChatMessage.system("s"), ChatMessage.user("1+2"),
                ChatMessage.assistant(null, List.of(call)),
                ChatMessage.toolResult(call.id(), "3")));

        Path fixture = temp.resolve("session.v1.jsonl");
        List<String> streamed = new ArrayList<>();
        RecordingChatModel recording = new RecordingChatModel(new MockChatModel());
        ChatResponse toolResponse = recording.complete(toolRound);
        ChatResponse textResponse = recording.completeStream(finalRound, streamed::add);
        recording.writeTo(fixture);

        assertThat(toolResponse.isToolRound()).isTrue();
        assertThat(textResponse.content()).isEqualTo("calculated: 3");
        assertThat(streamed).containsExactly("calculated: 3");

        // replay from the file: same responses, same chunk rhythm, script exhausted
        ReplayChatModel replay = new ReplayChatModel(fixture);
        assertThat(replay.complete(toolRound)).isEqualTo(toolResponse);
        List<String> replayedChunks = new ArrayList<>();
        assertThat(replay.completeStream(finalRound, replayedChunks::add)).isEqualTo(textResponse);
        assertThat(replayedChunks).containsExactlyElementsOf(streamed);
        assertThat(replay.remaining()).isZero();
    }

    @Test
    void replayFailsLoudOnRequestDrift(@TempDir Path temp) throws IOException {
        RecordingChatModel recording = new RecordingChatModel(new MockChatModel());
        ChatRequest recorded = request(List.of(ChatMessage.system("s"), ChatMessage.user("1+2")));
        recording.complete(recorded);
        Path fixture = temp.resolve("drift.jsonl");
        recording.writeTo(fixture);

        ReplayChatModel replay = new ReplayChatModel(fixture);
        ChatRequest drifted = request(List.of(ChatMessage.system("s"), ChatMessage.user("9*9")));
        assertThatThrownBy(() -> replay.complete(drifted))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("replay drift at completion 0");
    }

    @Test
    void replayFailsLoudWhenScriptExhausted(@TempDir Path temp) throws IOException {
        RecordingChatModel recording = new RecordingChatModel(new MockChatModel());
        ChatRequest single = request(List.of(ChatMessage.system("s"), ChatMessage.user("1+2")));
        recording.complete(single);
        Path fixture = temp.resolve("short.jsonl");
        recording.writeTo(fixture);

        ReplayChatModel replay = new ReplayChatModel(fixture);
        assertThat(replay.complete(single)).isNotNull(); // the one recorded call
        assertThatThrownBy(() -> replay.complete(single))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("replay exhausted");
    }
}
