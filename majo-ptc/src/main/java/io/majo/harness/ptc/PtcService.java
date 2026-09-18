package io.majo.harness.ptc;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The PTC runtime ({@code ctx.ptc}, dsh `ptc-runtime` analog): executes a
 * model-written JavaScript program in a fresh Node.js process and returns
 * the captured stdout. Stateless — every call gets a fresh process; state
 * flows through the code's own variables.
 *
 * <p>The core value: instead of N discrete tool calls (each costing a full
 * LLM round-trip), the model writes one program that does all N steps and
 * prints the final result. Tools are NOT callable from the program in v1 —
 * the model embeds the data inline.
 */
public final class PtcService extends Service {

    public static final String NAME = "ptc";
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    static final Logger LOG = LoggerFactory.getLogger(PtcService.class);

    private final String nodePath;
    private final long timeoutMillis;

    public PtcService(Context ctx, Object config) {
        super(ctx, NAME);
        this.nodePath = config instanceof java.util.Map<?, ?> map
                && map.get("nodePath") != null
                ? String.valueOf(map.get("nodePath")) : "node";
        long seconds = config instanceof java.util.Map<?, ?> map
                && map.get("timeoutSeconds") instanceof Number number
                && number.longValue() > 0 ? number.longValue() : DEFAULT_TIMEOUT_SECONDS;
        this.timeoutMillis = seconds * 1000;
    }

    /**
     * Executes a JavaScript program via Node.js and returns the captured
     * stdout. A non-zero exit or timeout throws {@link PtcException}.
     */
    public String execute(String program) {
        if (program == null || program.isBlank()) {
            throw new PtcException("ptc: program must not be blank");
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    nodePath, "-e", program, "--input-type=module");
            builder.redirectErrorStream(false);
            Process process = builder.start();
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new PtcException("ptc: execution timed out after "
                        + timeoutMillis + " ms");
            }
            String stdout = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new PtcException("ptc: exit " + process.exitValue()
                        + (stderr.isBlank() ? "" : ": " + stderr.strip()));
            }
            LOG.info("ptc: program executed ({} chars output)", stdout.length());
            return stdout.strip();
        } catch (IOException e) {
            throw new PtcException("ptc: cannot spawn " + nodePath
                    + " (is Node.js on PATH?): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PtcException("ptc: interrupted", e);
        }
    }
}
