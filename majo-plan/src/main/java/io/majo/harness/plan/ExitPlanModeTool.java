package io.majo.harness.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.interaction.InteractionService;
import io.majo.harness.interaction.Question;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.util.Map;

/**
 * {@code exit_plan_mode} (dsh plan-mode): the model calls it when its draft
 * plan is ready. The plan text goes to the human through the interaction
 * seam — "approve" records the plan's deactivation (PLAN_SET active=false)
 * and green-lights implementation; any other answer is feedback: the plan
 * stays active and the text rides back to the model in the tool result.
 * Calling it with no plan active fails loudly so spurious calls teach
 * themselves.
 */
public final class ExitPlanModeTool implements Tool {

    public static final String NAME = "exit_plan_mode";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Call this when your plan for the current task is complete. Your plan goes to "
                    + "the human for review: they approve it (proceed with implementation) or "
                    + "reply with feedback (revise the plan and call this again). Never call it "
                    + "with no plan active.",
            schema());

    private final SessionService sessions;
    private final InteractionService interactions;
    private final PlanState plans;

    public ExitPlanModeTool(SessionService sessions, InteractionService interactions,
            PlanState plans) {
        this.sessions = sessions;
        this.interactions = interactions;
        this.plans = plans;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("summary").put("type", "string");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.put("description", "Optional one-line summary of the plan for the review prompt.");
        return schema;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String sessionId = InteractionContext.sessionId();
        if (sessionId == null) {
            return ToolResult.error("exit_plan_mode runs inside a turn; no session is bound");
        }
        PlanState.Snapshot snapshot = plans.snapshot(sessionId);
        if (!snapshot.active()) {
            return ToolResult.error("no plan is active — nothing to review; "
                    + "a human starts plan mode with the /plan command");
        }
        String summary = summaryOf(call);
        String answer = interactions.ask(Question.ask(
                "Plan review" + (summary == null ? "" : ": " + summary) + "\n\n"
                        + snapshot.plan() + "\n\n"
                        + "Reply \"approve\" to accept this plan, or send your changes to keep planning.",
                InteractionContext.agent()));
        String normalized = answer == null ? "" : answer.trim();
        if (normalized.isEmpty() || normalized.equalsIgnoreCase("approve")
                || normalized.toLowerCase().startsWith("approve")) {
            sessions.append(sessionId, SessionEventType.PLAN_SET,
                    Map.of(SessionEvent.FIELD_ACTIVE, false,
                            SessionEvent.FIELD_PLAN, String.valueOf(snapshot.plan())));
            return ToolResult.ok("plan approved by the human — proceed with implementation",
                    Map.of("approved", true));
        }
        // feedback: the plan stays active; the answer rides the tool result,
        // which is durable and model-visible on the next step
        return ToolResult.ok("the human did not approve the plan yet. Their feedback: "
                + normalized + " — revise the plan and call exit_plan_mode again.",
                Map.of("approved", false));
    }

    private static String summaryOf(ToolCall call) {
        try {
            JsonNode root = MAPPER.readTree(call.arguments() == null ? "{}" : call.arguments());
            String summary = root.path("summary").asText("");
            return summary.isBlank() ? null : summary.trim();
        } catch (Exception e) {
            return null;
        }
    }
}
