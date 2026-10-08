package io.majo.harness.interaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.util.HashMap;
import java.util.Map;

/**
 * The model-facing {@code ask_user} tool (dsh tool-ask-user parity, timed
 * mode): poses a question to the human and waits at most {@code timeout}
 * seconds (default 120, {@code -1} = indefinitely). A timeout does NOT fail
 * the call — the tool returns a {@code pending} notice and the turn
 * continues (the model should proceed with independent work). The human's
 * late answer arrives later as a marked {@code [answer_to_pending_question]}
 * user message on the same session.
 *
 * <p>Config on the {@code ask-user} profile row:
 * {@code {mode: "timed"|"blocking", defaultTimeoutSeconds: 120}} —
 * {@code blocking} keeps the legacy ask (fail on timeout).
 */
public final class AskUserPlugin implements Plugin {

    public static final String NAME = "ask-user";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Object apply(Context ctx, Object config) {
        boolean timed = !(config instanceof Map<?, ?> map)
                || !"blocking".equals(String.valueOf(map.get("mode")));
        long defaultTimeout = config instanceof Map<?, ?> map
                && map.get("defaultTimeoutSeconds") instanceof Number number
                && number.longValue() > 0 ? number.longValue() : 120;
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        PendingAsks asks = new PendingAsks(ctx);
        return tools.register(new AskUserTool(asks, timed, defaultTimeout));
    }

    /** The seam between the tool and the delivery world (test overridable). */
    public static class PendingAsks {

        private final Context ctx;

        public PendingAsks(Context ctx) {
            this.ctx = ctx;
        }

        /**
         * Waits for the human's answer; {@code null} on timeout (the turn
         * continues). Default implementation routes through the front
         * interaction handler synchronously (the web handler's timed mode
         * honors the window); headless fallbacks answer canned text.
         */
        public String await(Question question, long timeoutSeconds) {
            InteractionService interactions = ctx.get(InteractionService.NAME);
            if (interactions == null) {
                return "";
            }
            return interactions.askTimed(question, timeoutSeconds);
        }

        /** Whether the caller may ask at all (a session must be bound). */
        public String session() {
            return InteractionContext.sessionId();
        }
    }

    private static final class AskUserTool implements Tool {

        private final PendingAsks asks;
        private final boolean timed;
        private final long defaultTimeout;

        AskUserTool(PendingAsks asks, boolean timed, long defaultTimeout) {
            this.asks = asks;
            this.timed = timed;
            this.defaultTimeout = defaultTimeout;
        }

        @Override
        public ToolSpec spec() {
            ObjectNode properties = MAPPER.createObjectNode();
            properties.putObject("question").put("type", "string")
                    .put("description", "The question for the human, phrased to need one answer.");
            properties.putObject("timeout").put("type", "integer")
                    .put("description",
                            "Seconds to wait (default " + defaultTimeout + "; -1 = wait indefinitely). "
                                    + "On timeout the call returns pending and the turn continues; "
                                    + "the human's late answer arrives later as a marked user message.");
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", properties);
            schema.putArray("required").add("question");
            return new ToolSpec("ask_user",
                    "Ask the human a question and wait briefly for the answer. Use when you "
                            + "genuinely need human input to proceed. On timeout you get a pending "
                            + "notice — keep working on anything independent; the answer will "
                            + "arrive later as an [answer_to_pending_question] message.",
                    schema);
        }

        @Override
        public ToolResult execute(ToolCall call) {
            try {
                JsonNode args = MAPPER.readTree(call.arguments());
                String question = args.path("question").asText("");
                if (question.isBlank()) {
                    return ToolResult.error("ask_user: pass a question");
                }
                long timeout = args.path("timeout").isNumber()
                        ? args.path("timeout").asLong() : defaultTimeout;
                String sessionId = asks.session();
                if (sessionId == null) {
                    return ToolResult.error("ask_user runs inside a turn; no session is bound");
                }
                if (InteractionContext.delegationDepth() > 0) {
                    return ToolResult.error("ask_user: delegated children cannot ask the "
                            + "human directly — surface the question in your report instead");
                }
                Question asked = Question.ask(question, InteractionContext.agent());
                String answer = timed
                        ? asks.await(asked, timeout)
                        : nullOnBlank(asks.await(asked, defaultTimeout));
                if (answer == null) {
                    return ToolResult.ok("pending: the human has not answered within "
                            + (timeout < 0 ? "the wait window" : timeout + "s")
                            + ". This is NOT a refusal — continue with any independent work. "
                            + "The answer will arrive later as an [answer_to_pending_question] "
                            + "user message.", Map.of("pending", true, "questionId", asked.id()));
                }
                return ToolResult.ok("answer: " + answer,
                        Map.of("pending", false, "questionId", asked.id()));
            } catch (Exception e) {
                return ToolResult.error("ask_user: " + e.getMessage());
            }
        }

        private static String nullOnBlank(String answer) {
            return answer == null || answer.isBlank() ? null : answer;
        }
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
