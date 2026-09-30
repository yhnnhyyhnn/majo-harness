package io.majo.harness.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolEvents;
import io.majo.harness.tools.ToolResult;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The repeat-call reminder (dsh guard repeat-tool-reminder parity, deepened
 * from the old in-registry sketch): consecutive <em>semantically identical</em>
 * calls of one tool — arguments compared by a deep key-sorted canonical form,
 * so key order and whitespace do not matter — get an advisory line appended
 * nudging the model to change approach.
 *
 * <p>Differences from the old sketch, all dsh parity: chains are
 * <b>per-session</b> (InteractionContext-bound, no cross-session crosstalk);
 * counting happens at {@code tools/pre-execute}, so <b>denied calls count
 * too</b> — and because the advisory wraps whatever the rest of the chain
 * returns, a denial result carries the reminder instead of losing it; and
 * the chain <b>resets when a human turn opens</b> ({@code agent/turn-opened}
 * with a non-goal producer), the user-interruption signal.
 *
 * <p>Config: {@code {repeatReminder: true}} on the {@code repeat-reminder}
 * profile row (opt-in, like the old tools-row flag).
 */
public final class RepeatReminderPlugin implements Plugin {

    public static final String NAME = "repeat-reminder";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean enabled;
    private final Map<String, Chain> chains = new ConcurrentHashMap<>();

    private record Chain(String signature, int count) {}

    public RepeatReminderPlugin() {
        this(true);
    }

    RepeatReminderPlugin(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public Object apply(Context ctx, Object config) {
        boolean on = enabled && !(config instanceof Map<?, ?> map
                && Boolean.FALSE.equals(map.get("repeatReminder")));
        if (!on) {
            return null;
        }
        return io.majo.harness.util.Disposables.composite(
                ctx.on(ToolEvents.PRE_EXECUTE, (thisArg, args) ->
                        observe((ToolCall) args[0],
                                (java.util.function.Supplier<Object>) args[args.length - 1])),
                ctx.on(AgentLoopEvents.TURN_OPENED, (thisArg, args) -> {
                    String producer = args.length > 1 && args[1] != null
                            ? String.valueOf(args[1]) : null;
                    if (!"goal".equals(producer)) {
                        // a human (or host) turn: the model changed approach
                        chains.remove(String.valueOf(args[0]));
                    }
                    return null;
                }));
    }

    /**
     * Counts the call in the session's chain, then wraps the rest of the
     * pipeline: repeat #2 and beyond get the advisory appended to whatever
     * outcome the chain produced — success, failure, or denial.
     */
    private Object observe(ToolCall call, java.util.function.Supplier<Object> next) {
        // session-bound when inside a turn; a shared chain otherwise (matches
        // the old in-registry behavior for unbound callers)
        String session = InteractionContext.sessionId();
        String chainKey = session == null ? "_" : session;
        Object outcome = next.get();
        if (outcome == null) {
            return outcome;
        }
        String signature = call.name() + ":" + canonical(call.arguments());
        Chain chain = chains.merge(chainKey, new Chain(signature, 1),
                (existing, ignored) -> signature.equals(existing.signature())
                        ? new Chain(signature, existing.count() + 1)
                        : new Chain(signature, 1));
        if (chain.count() < 2 || !(outcome instanceof ToolResult result)) {
            return outcome;
        }
        String advisory = "\n[advisory: identical repeat call #" + chain.count()
                + " — same tool and semantically identical arguments as the previous "
                + "call; consider a different approach if this one is not working]";
        if (result.ok()) {
            return result.content() == null ? result
                    : new ToolResult(true, result.content() + advisory, null, result.data());
        }
        return new ToolResult(false, null,
                (result.error() == null ? "" : result.error()) + advisory, result.data());
    }

    /** Deep key-sorted canonical JSON (order- and whitespace-insensitive). */
    static String canonical(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        try {
            return canonical(MAPPER.readTree(arguments));
        } catch (Exception e) {
            return arguments.strip(); // unparseable: raw text fallback
        }
    }

    private static String canonical(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            TreeMap<String, String> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                sorted.put(field.getKey(), canonical(field.getValue()));
            }
            return "{" + sorted.entrySet().stream()
                    .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
                    .reduce((a, b) -> a + "," + b).orElse("") + "}";
        }
        if (node.isArray()) {
            StringBuilder array = new StringBuilder("[");
            for (JsonNode item : node) {
                array.append(canonical(item)).append(',');
            }
            return array.append(']').toString();
        }
        if (node.isTextual()) {
            return "\"" + node.textValue() + "\"";
        }
        return node.toString();
    }

    @Override
    public String name() {
        return NAME;
    }
}
