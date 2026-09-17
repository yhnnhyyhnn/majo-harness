package io.majo.harness.llm.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One recorded model completion: the exact request, the streamed text chunks
 * (empty for non-streaming calls), and the final response. The wire format is
 * JSONL — a header line {@code {"format":"majo-llm-replay","version":1}}
 * followed by one {@link LlmTranscript} per completion.
 *
 * <p>This is test-support infrastructure (dsh {@code llm-replay} analog):
 * record a real provider once, replay it in tests forever after.
 */
public record LlmTranscript(ChatRequest request, List<String> chunks, ChatResponse response) {

    public static final String FORMAT = "majo-llm-replay";
    public static final int VERSION = 1;

    public LlmTranscript {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        JSON.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    }

    /** Writes header + transcripts as JSONL to {@code file} (parents created). */
    public static void write(Path file, List<LlmTranscript> transcripts) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write((JSON.writeValueAsString(java.util.Map.of("format", FORMAT, "version", VERSION))
                    + "\n").getBytes(StandardCharsets.UTF_8));
            for (LlmTranscript transcript : transcripts) {
                out.write((JSON.writeValueAsString(transcript) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /** Reads a fixture written by {@link #write}, failing loud on a foreign file. */
    public static List<LlmTranscript> read(Path file) throws IOException {
        List<LlmTranscript> transcripts = new ArrayList<>();
        boolean headerSeen = false;
        try (InputStream in = Files.newInputStream(file)) {
            String[] lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n");
            for (int index = 0; index < lines.length; index++) {
                String line = lines[index].trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (!headerSeen) {
                    var header = JSON.readTree(line);
                    if (!FORMAT.equals(header.path("format").asText())
                            || header.path("version").asInt() != VERSION) {
                        throw new IOException("not an " + FORMAT + " v" + VERSION + " fixture: " + file);
                    }
                    headerSeen = true;
                    continue;
                }                try {
                    transcripts.add(JSON.readValue(line, LlmTranscript.class));
                } catch (IOException e) {
                    throw new IOException("fixture line " + (index + 1) + " is not a transcript: "
                            + e.getMessage(), e);
                }
            }
        }
        if (!headerSeen) {
            throw new IOException("empty or headerless replay fixture: " + file);
        }
        return List.copyOf(transcripts);
    }
}
