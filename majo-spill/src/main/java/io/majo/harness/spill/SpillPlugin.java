package io.majo.harness.spill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolEvents;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import io.majo.harness.util.Disposables;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The spill policy (dsh `spill-policy` analog, roadmap-0.5 candidate
 * adopted): when a tool result's content exceeds {@code maxInlineBytes},
 * the full text is stored out-of-band and the model sees a preview plus a
 * locator — retrieve the whole output with the namespaced-agnostic
 * {@code spill_read} tool. Storage failure keeps the original result
 * (fail-open: a spill hiccup must never lose a tool's answer). Disabled
 * unless {@code maxInlineBytes} is set.
 *
 * <p>Config: {@code {path: "~/.majo-harness/spill", maxInlineBytes: <n>,
 * previewChars: <n>}}.
 */
public final class SpillPlugin implements Plugin {

    public static final String NAME = "spill";
    public static final int DEFAULT_PREVIEW_CHARS = 2_000;

    static final Logger LOG = LoggerFactory.getLogger(SpillPlugin.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Object apply(Context ctx, Object config) {
        Map<?, ?> map = config instanceof Map<?, ?> m ? m : Map.of();
        Path directory = Path.of(expandHome(map.get("path") == null
                ? "~/.majo-harness/spill"
                : String.valueOf(map.get("path"))));
        long maxInlineBytes = map.get("maxInlineBytes") instanceof Number number
                && number.longValue() > 0 ? number.longValue() : 0;
        int previewChars = map.get("previewChars") instanceof Number number
                && number.intValue() > 0 ? number.intValue() : DEFAULT_PREVIEW_CHARS;

        SpillStore store;
        try {
            store = new SpillStore(ctx, directory);
        } catch (IOException e) {
            throw new IllegalStateException("spill: cannot create store directory "
                    + directory, e);
        }
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        List<Disposable> registrations = new ArrayList<>();
        registrations.add(tools.register(new SpillReadTool(store)));
        if (maxInlineBytes > 0) {
            registrations.add(ctx.on(ToolEvents.POST_EXECUTE, (thisArg, args) -> {
                ToolCall call = (ToolCall) args[0];
                ToolResult result = (ToolResult) args[1];
                @SuppressWarnings("unchecked")
                java.util.function.Supplier<Object> next =
                        (java.util.function.Supplier<Object>) args[args.length - 1];
                // spill_read itself is exempt: retrieving a spill must never re-spill
                if ("spill_read".equals(call.name())) {
                    return next.get();
                }
                ToolResult replacement =
                        spillIfNeeded(store, call, result, maxInlineBytes, previewChars);
                return replacement != null ? replacement : next.get();
            }));
        }
        return Disposables.composite(registrations);
    }

    /**
     * Replaces an oversized successful text result with a preview + locator;
     * {@code null} (pipeline continues unchanged) for small, failed, or
     * already-spilled results and on storage failure.
     */
    private ToolResult spillIfNeeded(SpillStore store, ToolCall call, ToolResult result,
            long maxInlineBytes, int previewChars) {
        if (result == null || !result.ok() || result.content() == null) {
            return null;
        }
        if (result.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= maxInlineBytes) {
            return null;
        }
        String content = result.content();
        try {
            String id = store.save(content);
            int preview = Math.min(previewChars, content.length());
            String replacement = content.substring(0, preview)
                    + "\n\n[output truncated: " + content.length()
                    + " chars stored out-of-band. Use spill_read with id \"" + id
                    + "\" to retrieve the full text.]";
            LOG.info("spill: tool \"{}\" output ({} chars) stored as {}", call.name(),
                    content.length(), id);
            return new ToolResult(true, replacement, null, result.data());
        } catch (IOException e) {
            // fail-open: keep the original inline result
            LOG.warn("spill: cannot store output of tool \"{}\": {}", call.name(), e.getMessage());
            return null;
        }
    }

    /** The read-back tool: full stored text by locator id. */
    private static final class SpillReadTool implements Tool {

        private final SpillStore store;

        SpillReadTool(SpillStore store) {
            this.store = store;
        }

        @Override
        public ToolSpec spec() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties").putObject("id").put("type", "string");
            schema.putArray("required").add("id");
            return new ToolSpec("spill_read",
                    "Retrieves the full text of a tool output that was stored out-of-band "
                            + "(id comes from a truncation notice in a previous tool result).",
                    schema);
        }

        @Override
        public ToolResult execute(ToolCall call) {
            try {
                JsonNode args = call.arguments() == null || call.arguments().isBlank()
                        ? MAPPER.createObjectNode()
                        : MAPPER.readTree(call.arguments());
                String id = args.path("id").asText("");
                String content = store.read(id);
                if (content == null) {
                    return ToolResult.error("spill_read: unknown id \"" + id + "\"");
                }
                return ToolResult.ok(content, Map.of());
            } catch (IOException e) {
                return ToolResult.error("spill_read: " + e.getMessage());
            }
        }
    }

    private static String expandHome(String value) {
        if (value.startsWith("~")) {
            return System.getProperty("user.home") + value.substring(1);
        }
        return value;
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(ToolRegistry.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
