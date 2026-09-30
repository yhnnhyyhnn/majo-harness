package io.majo.harness.ptc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The PTC runtime ({@code ctx.ptc}, dsh `ptc-runtime` analog): executes a
 * model-written JavaScript program in a fresh Node.js process. Stateless —
 * every call gets a fresh process; state flows through the code's own
 * variables.
 *
 * <p>The core value: instead of N discrete tool calls (each costing a full
 * LLM round-trip), the model writes one program that does all N steps.
 * Programs call host tools directly — {@code await tools.read_file({path})}
 * resolves with the tool's (JSON-decoded when possible) content and rejects
 * on tool failure — riding a JSON-lines control protocol: the child speaks
 * {@code call}/{@code result}/{@code error} frames on stdout, replies arrive
 * on stdin, and the program's {@code console.*} output is redirected to
 * stderr so the channel stays clean. Nested dispatch is sequential (one call
 * at a time, on the calling turn's thread) so approval gates apply unchanged.
 * Completion: the program calls {@code result(value)}; a program that never
 * does gets its printed (stderr) output returned instead — the pre-callback
 * contract, preserved as a fallback.
 */
public final class PtcService extends Service {

    public static final String NAME = "ptc";
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_MAX_TOOL_CALLS = 64;
    static final Logger LOG = LoggerFactory.getLogger(PtcService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String nodePath;
    private final long timeoutMillis;
    private final int maxToolCalls;
    private volatile Path shim;

    public PtcService(Context ctx, Object config) {
        super(ctx, NAME);
        this.nodePath = config instanceof java.util.Map<?, ?> map
                && map.get("nodePath") != null
                ? String.valueOf(map.get("nodePath")) : "node";
        long seconds = config instanceof java.util.Map<?, ?> map
                && map.get("timeoutSeconds") instanceof Number number
                && number.longValue() > 0 ? number.longValue() : DEFAULT_TIMEOUT_SECONDS;
        this.timeoutMillis = seconds * 1000;
        this.maxToolCalls = config instanceof java.util.Map<?, ?> map
                && map.get("maxToolCalls") instanceof Number number
                && number.longValue() > 0 ? number.intValue() : DEFAULT_MAX_TOOL_CALLS;
    }

    /**
     * Executes a JavaScript program; host tools resolve through
     * {@code tools}. Returns the final answer text. A non-zero exit, timeout,
     * or program error throws {@link PtcException}.
     */
    public String execute(String program, ToolRegistry tools) {
        if (program == null || program.isBlank()) {
            throw new PtcException("ptc: program must not be blank");
        }
        Path programFile = null;
        Process process = null;
        try {
            programFile = Files.createTempFile("majo-ptc-", ".mjs");
            Files.writeString(programFile, program, StandardCharsets.UTF_8);
            process = new ProcessBuilder(nodePath, shim().toString(), programFile.toString())
                    .redirectErrorStream(false)
                    .start();
            Outcome outcome = drive(process, tools);
            LOG.info("ptc: program executed ({} tool calls)", outcome.toolCalls);
            return outcome.answer;
        } catch (IOException e) {
            throw new PtcException("ptc: cannot spawn " + nodePath
                    + " (is Node.js on PATH?): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PtcException("ptc: interrupted", e);
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
            if (programFile != null) {
                try {
                    Files.deleteIfExists(programFile);
                } catch (IOException ignored) {
                    // temp file best-effort cleanup
                }
            }
        }
    }

    /** One run's result: the answer text plus the nested tool-call count. */
    private record Outcome(String answer, int toolCalls) {}

    /**
     * The protocol loop: reads child stdout frames, executes nested tool
     * calls sequentially on the calling thread (approval gates apply), writes
     * replies to the child's stdin. A watchdog enforces the total deadline —
     * the reader cannot hang forever on a silent child.
     */
    private Outcome drive(Process process, ToolRegistry tools)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        AtomicReference<String> stderr = new AtomicReference<>("");
        Thread stderrDrain = Thread.ofVirtual().start(() -> {
            try (InputStream stream = process.getErrorStream()) {
                stderr.set(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // the process was destroyed; whatever was captured governs
            }
        });
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(timeoutMillis);
                process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            int toolCalls = 0;
            JsonNode resultValue = null;
            String note = null;
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode frame;
                try {
                    frame = MAPPER.readTree(line);
                } catch (IOException malformed) {
                    continue; // tolerate stray non-JSON lines
                }
                String type = frame.path("type").asText("");
                if ("call".equals(type)) {
                    toolCalls++;
                    if (toolCalls > maxToolCalls) {
                        throw new PtcException("ptc: program exceeded maxToolCalls ("
                                + maxToolCalls + ")");
                    }
                    writeReply(writer, frame.path("id").asInt(),
                            tools.execute(ToolCall.of(
                                    frame.path("name").asText(""),
                                    frame.path("args").toString())));
                } else if ("result".equals(type)) {
                    resultValue = frame.path("value");
                    note = frame.hasNonNull("note") ? frame.path("note").asText() : null;
                    break;
                } else if ("error".equals(type)) {
                    throw new PtcException("ptc: " + frame.path("message").asText("program error"));
                }
                // unknown frame types are ignored (forward compatibility)
            }
            boolean explicitResult = resultValue != null && !resultValue.isMissingNode()
                    && !resultValue.isNull();
            if (!process.waitFor(remaining(deadline), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new PtcException("ptc: execution timed out after " + timeoutMillis + " ms");
            }
            if (explicitResult) {
                return new Outcome(format(resultValue, note), toolCalls);
            }
            String printed = stderr.get().strip();
            if (process.exitValue() != 0) {
                if (System.currentTimeMillis() >= deadline) {
                    // the watchdog got it: report the deadline, not the exit code
                    throw new PtcException("ptc: execution timed out after "
                            + timeoutMillis + " ms");
                }
                throw new PtcException("ptc: exit " + process.exitValue()
                        + (printed.isEmpty() ? "" : ": " + printed));
            }
            // no explicit result: the printed output IS the answer (the
            // pre-callback contract) — plus the "no result" note when the
            // shim announced the fallback
            String answer = printed.isEmpty()
                    ? "(program completed with no output)" : printed;
            if (note != null) {
                answer = answer + "\n\n(" + note + ")";
            }
            return new Outcome(answer, toolCalls);
        } finally {
            watchdog.interrupt();
            stderrDrain.join(1000);
        }
    }

    private static void writeReply(BufferedWriter writer, int id, ToolResult result)
            throws IOException {
        ObjectNode reply = MAPPER.createObjectNode()
                .put("type", "reply")
                .put("id", id)
                .put("ok", result.ok());
        if (result.ok()) {
            JsonNode value;
            try {
                value = MAPPER.readTree(result.content());
            } catch (IOException notJson) {
                value = MAPPER.getNodeFactory().textNode(result.content());
            }
            reply.set("value", value);
        } else {
            reply.put("message", result.visibleText());
        }
        writer.write(MAPPER.writeValueAsString(reply));
        writer.newLine();
        writer.flush();
    }

    /** A string result stays a string; any other JSON value renders pretty. */
    private static String format(JsonNode value, String note) {
        String text;
        if (value == null || value.isNull() || value.isMissingNode()) {
            text = null;
        } else if (value.isTextual()) {
            text = value.asText();
        } else {
            try {
                text = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            } catch (IOException e) {
                text = String.valueOf(value);
            }
        }
        if (text == null || text.isBlank()) {
            text = "(program returned no result)";
        }
        return note == null ? text : text + "\n\n(" + note + ")";
    }

    private static long remaining(long deadline) {
        long left = deadline - System.currentTimeMillis();
        return Math.max(1, left);
    }

    /** Extracts the bundled shim to a temp file once (node cannot import from a jar). */
    private Path shim() throws IOException {
        Path cached = shim;
        if (cached != null && Files.exists(cached)) {
            return cached;
        }
        try (InputStream in = PtcService.class.getResourceAsStream("/ptc-shim.mjs")) {
            if (in == null) {
                throw new IOException("bundled ptc-shim.mjs missing from the classpath");
            }
            Path file = Files.createTempFile("majo-ptc-shim-", ".mjs");
            Files.copy(in, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            file.toFile().deleteOnExit();
            this.shim = file;
            return file;
        }
    }
}
