package io.majo.harness.goal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.agent.loop.AgentLoopService;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolSpec;
import io.majo.harness.util.Disposables;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The goal tool consumer (dsh tool-goal parity): registers
 * {@code get_goal}/{@code create_goal}/{@code update_goal} plus the static
 * {@code goal-tools} system section. Authority rules — create/edit/pause/
 * resume require a direct human message in the open turn; complete/blocked
 * are additionally allowed inside a current goal round; blocked carries the
 * consecutive-rounds floor for autonomous reports. After an autonomous
 * complete/blocked the wrapup context lands as a durable note at the next
 * step boundary of the same turn.
 */
public final class GoalToolPlugin implements Plugin {

    public static final String NAME = "goal-tools";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GoalService goals;
    private final AgentLoopService loop;

    @Override
    public Object apply(Context ctx, Object config) {
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        GoalService goalService = this.goals != null ? this.goals : ctx.get(GoalService.NAME);
        AgentLoopService agentLoop = this.loop != null ? this.loop : ctx.get(AgentLoopService.NAME);
        if (goalService == null) {
            throw new IllegalStateException("goal-tools: the goal module is not mounted");
        }
        List<Disposable> registrations = new ArrayList<>();
        registrations.add(tools.register(new GetGoalTool(goalService)));
        registrations.add(tools.register(new CreateGoalTool(goalService)));
        registrations.add(tools.register(new UpdateGoalTool(goalService, agentLoop)));
        if (agentLoop != null) {
            registrations.add(agentLoop.registerSystemSection("goal-tools",
                    () -> systemSection(goalService)));
        }
        return Disposables.composite(registrations);
    }

    // test seam
    GoalToolPlugin(GoalService goals, AgentLoopService loop) {
        this.goals = goals;
        this.loop = loop;
    }

    public GoalToolPlugin() {
        this(null, null);
    }

    /** The static rules section (dsh tool:goal text, majo wording). */
    static String systemSection(GoalService goals) {
        return "A session goal is a durable objective the harness keeps working toward, "
                + "round after round. Rules:\n"
                + "- create_goal may be used only when the current turn contains a direct "
                + "human request (a real user message). You cannot start a goal on your own.\n"
                + "- After a restart or crash an active goal stays disarmed: do not assume "
                + "it will auto-continue; ask the user to resume it (update_goal action=resume "
                + "is human-only too).\n"
                + "- Mark a goal complete (update_goal action=complete) only when the "
                + "objective is actually achieved — inside a goal round you may do this "
                + "autonomously.\n"
                + "- Mark a goal blocked (update_goal action=blocked, with a code and a "
                + "message) only when the same blocking condition has persisted for at least "
                + goals.blockedAfter() + " consecutive goal rounds. Difficulty, uncertainty, "
                + "or useful remaining work is not blocked.\n"
                + "- Every update carries the goal's current goal_id and revision; a stale "
                + "revision fails and you must re-read with get_goal.";
    }

    // ----- tools -----

    private static final class GetGoalTool implements Tool {

        private final GoalService goals;

        GetGoalTool(GoalService goals) {
            this.goals = goals;
        }

        @Override
        public ToolSpec spec() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties");
            return new ToolSpec("get_goal",
                    "Returns the session's current goal (objective, phase, revision, rounds) "
                            + "or a notice that no goal is active.",
                    schema);
        }

        @Override
        public ToolResult execute(ToolCall call) {
            String sessionId = InteractionContext.sessionId();
            if (sessionId == null) {
                return ToolResult.error("get_goal runs inside a turn; no session is bound");
            }
            GoalService.Goal goal = goals.get(sessionId);
            if (goal == null) {
                return ToolResult.ok("no active goal on this session");
            }
            return ToolResult.ok(describe(goal, goals.roundsStarted(sessionId)),
                    Map.of("goalId", goal.goalId(), "revision", goal.revision(),
                            "phase", goal.phase().name().toLowerCase()));
        }
    }

    private static final class CreateGoalTool implements Tool {

        private final GoalService goals;

        CreateGoalTool(GoalService goals) {
            this.goals = goals;
        }

        @Override
        public ToolSpec spec() {
            ObjectNode properties = MAPPER.createObjectNode();
            properties.putObject("objective").put("type", "string")
                    .put("description", "The durable objective, phrased so completion is checkable.");
            properties.putObject("max_rounds").put("type", "integer")
                    .put("description", "Optional round budget (default 256).");
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", properties);
            schema.putArray("required").add("objective");
            return new ToolSpec("create_goal",
                    "Creates this session's durable goal and arms auto-continue. Only valid "
                            + "when the current turn contains a direct human request.",
                    schema);
        }

        @Override
        public ToolResult execute(ToolCall call) {
            String sessionId = InteractionContext.sessionId();
            if (sessionId == null) {
                return ToolResult.error("create_goal runs inside a turn; no session is bound");
            }
            try {
                JsonNode args = MAPPER.readTree(call.arguments());
                String objective = args.path("objective").asText("");
                if (objective.isBlank()) {
                    return ToolResult.error("create_goal: pass an objective");
                }
                if (!goals.hasDirectHumanInput(sessionId)) {
                    return ToolResult.error("create_goal requires a direct human request: "
                            + "the current turn has no real user message");
                }
                Long maxRounds = args.path("max_rounds").isNumber()
                        ? args.path("max_rounds").asLong() : null;
                GoalService.Goal goal = goals.create(sessionId, objective, maxRounds);
                return ToolResult.ok("goal created " + goal.ref() + " (armed): " + describe(
                        goal, 0), Map.of("goalId", goal.goalId(), "revision", goal.revision()));
            } catch (GoalException e) {
                return ToolResult.error(e.getMessage());
            } catch (Exception e) {
                return ToolResult.error("create_goal: cannot parse arguments: " + e.getMessage());
            }
        }
    }

    private static final class UpdateGoalTool implements Tool {

        private final GoalService goals;
        private final AgentLoopService loop;

        UpdateGoalTool(GoalService goals, AgentLoopService loop) {
            this.goals = goals;
            this.loop = loop;
        }

        @Override
        public ToolSpec spec() {
            ObjectNode properties = MAPPER.createObjectNode();
            properties.putObject("goal_id").put("type", "string");
            properties.putObject("revision").put("type", "integer")
                    .put("description", "The goal's current revision (CAS guard).");
            properties.putObject("action").put("type", "string")
                    .put("description", "edit | pause | resume | complete | blocked");
            properties.putObject("objective").put("type", "string")
                    .put("description", "edit: the new objective (human-only action).");
            properties.putObject("max_rounds").put("type", "integer")
                    .put("description", "edit: new round budget (human-only action).");
            properties.putObject("code").put("type", "string")
                    .put("description", "blocked: short lower-kebab reason code.");
            properties.putObject("message").put("type", "string")
                    .put("description", "blocked: what exactly is blocking progress.");
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", properties);
            schema.putArray("required").add("goal_id").add("revision").add("action");
            return new ToolSpec("update_goal",
                    "Updates the session goal: edit the objective (human-only), pause/resume "
                            + "(human-only), complete, or report blocked (autonomous only after "
                            + "the same condition persisted for the configured consecutive "
                            + "rounds). Carries the current revision as a CAS guard.",
                    schema);
        }

        @Override
        public ToolResult execute(ToolCall call) {
            String sessionId = InteractionContext.sessionId();
            if (sessionId == null) {
                return ToolResult.error("update_goal runs inside a turn; no session is bound");
            }
            try {
                JsonNode args = MAPPER.readTree(call.arguments());
                String goalId = args.path("goal_id").asText("");
                long revision = args.path("revision").asLong(0);
                String action = args.path("action").asText("");
                if (goalId.isBlank() || action.isBlank()) {
                    return ToolResult.error("update_goal: goal_id, revision and action are required");
                }
                GoalService.Ref ref = new GoalService.Ref(goalId, revision);
                boolean human = goals.hasDirectHumanInput(sessionId);
                GoalService.Goal goal = goals.get(sessionId);
                boolean inGoalRound = goal != null
                        && goal.goalId().equals(goalId)
                        && goals.hasGoalRoundInput(sessionId, goalId);
                switch (action) {
                    case "edit" -> {
                        requireHuman(human);
                        goal = goals.edit(sessionId, ref,
                                textOrNull(args, "objective"),
                                args.path("max_rounds").isNumber()
                                        ? args.path("max_rounds").asLong() : null);
                    }
                    case "pause" -> {
                        requireHuman(human);
                        goal = goals.pause(sessionId, ref, false);
                    }
                    case "resume" -> {
                        requireHuman(human);
                        goal = goals.resume(sessionId, ref);
                    }
                    case "complete" -> {
                        requireAuthority(human, inGoalRound);
                        goal = goals.complete(sessionId, ref);
                    }
                    case "blocked" -> {
                        requireAuthority(human, inGoalRound);
                        if (!human) {
                            // the autonomous floor: enough admitted rounds first
                            if (goals.roundsStarted(sessionId) < goals.blockedAfter()) {
                                return ToolResult.error("blocked requires the same blocking "
                                        + "condition to persist for at least "
                                        + goals.blockedAfter() + " consecutive goal rounds; "
                                        + "ask the user instead");
                            }
                        }
                        goal = goals.block(sessionId, ref, args.path("code").asText("model-reported"),
                                args.path("message").asText(""));
                    }
                    default -> {
                        return ToolResult.error("update_goal: unknown action \"" + action + "\"");
                    }
                }
                wrapupIfAutonomous(action, human, sessionId, goal);
                return ToolResult.ok("goal " + action + "d: " + describe(goal,
                        goals.roundsStarted(sessionId)),
                        Map.of("goalId", goal.goalId(), "revision", goal.revision(),
                                "phase", goal.phase().name().toLowerCase()));
            } catch (GoalException e) {
                return ToolResult.error(e.getMessage());
            } catch (Exception e) {
                return ToolResult.error("update_goal: cannot parse arguments: " + e.getMessage());
            }
        }

        /**
         * After an autonomous complete/blocked inside a goal round, inject the
         * wrapup context (dsh deferContext parity) — it lands at the next step
         * boundary of this same turn.
         */
        private void wrapupIfAutonomous(String action, boolean human,
                String sessionId, GoalService.Goal goal) {
            if (human || loop == null || goal == null) {
                return;
            }
            boolean terminal = "complete".equals(action) || "blocked".equals(action);
            if (!terminal) {
                return;
            }
            StringBuilder wrapup = new StringBuilder();
            wrapup.append("complete".equals(action) ? "<goal_complete>" : "<goal_blocked>")
                    .append("\nObjective: ").append(goal.objective());
            if ("blocked".equals(action)) {
                wrapup.append("\nBlocked: ").append(goal.blockedCode()).append(" — ")
                        .append(goal.blockedMessage());
            }
            wrapup.append("\n\nWrite your closing message now: state the outcome, summarize "
                    + "what was done and how it was verified, and point to concrete artifacts "
                    + "(files, commits). Do not call any more tools.");
            loop.inject(sessionId, wrapup.toString());
        }

        private static void requireHuman(boolean human) {
            if (!human) {
                throw new GoalException("update_goal: this action requires a direct human "
                        + "request — the current turn has no real user message");
            }
        }

        private static void requireAuthority(boolean human, boolean inGoalRound) {
            if (!human && !inGoalRound) {
                throw new GoalException("update_goal: complete/blocked are allowed only "
                        + "inside a goal round or by direct human request");
            }
        }

        private static String textOrNull(JsonNode args, String key) {
            return args.hasNonNull(key) ? args.get(key).asText() : null;
        }
    }

    private static String describe(GoalService.Goal goal, long rounds) {
        StringBuilder text = new StringBuilder();
        text.append("phase=").append(goal.phase().name().toLowerCase());
        text.append(" revision=").append(goal.revision());
        text.append(" rounds=").append(rounds).append('/').append(goal.maxRounds());
        if (goal.phase() == GoalService.Phase.BLOCKED) {
            text.append(" blocked=").append(goal.blockedCode()).append(": ")
                    .append(goal.blockedMessage());
        }
        text.append("\nobjective: ").append(goal.objective());
        return text.toString();
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(ToolRegistry.NAME, null);
        inject.put(GoalService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
