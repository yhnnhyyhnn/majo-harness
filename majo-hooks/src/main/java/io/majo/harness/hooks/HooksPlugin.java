package io.majo.harness.hooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.agent.loop.AgentLoopEvents;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import io.majo.harness.shell.ShellService;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolEvents;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.util.Disposables;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The hooks compatibility bridge (dsh hook-protocol analog): runs Claude
 * Code / Codex {@code hooks.json} command hooks on the waterfall surface.
 * Three points are bridged, the practically-used subset:
 *
 * <ul>
 *   <li><b>PreToolUse</b> — a blocking gate on {@code tools/pre-execute}:
 *       exit 2 (stderr = reason), {@code continue:false}, or a
 *       {@code permissionDecision} of deny turns the call into a model-visible
 *       error result; allow/ask are advisory (the approval seam stays the
 *       authority);</li>
 *   <li><b>UserPromptSubmit</b> — on {@code agent/user-submit}: non-zero exit
 *       or a halt request rejects the submission loudly (nothing was logged);
 *       stdout context rides into the turn as durable replacement suffix —
 *       the returned text is exactly what gets logged, preserving
 *       "model-visible means logged";</li>
 *   <li><b>Stop</b> — after the loop's turn converges: non-blocking; stdout
 *       context lands as a durable CONTEXT_NOTE on the session.</li>
 * </ul>
 *
 * <p>Every invocation appends the durable {@code HOOK_INVOKED} +
 * {@code HOOK_RESULT} audit pair (bounded stderr, wall-clock duration) when a
 * session is bound. Fail-open: a hook that cannot run blocks nothing.
 *
 * <p>Config: {@code {path: "hooks.json"}} (a Claude Code-style file) or
 * inline {@code {hooks: {PreToolUse: [...]}}} — plus optional
 * {@code workdir} (default: process cwd) and {@code timeoutSeconds}
 * (default 600).
 */
public final class HooksPlugin implements Plugin {

    public static final String NAME = "hooks";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong HANDLER_IDS = new AtomicLong();

    private final HooksConfig config;
    private final String workdir;
    private final SessionService sessions;
    private final HookRunner runner;

    @Override
    public Object apply(Context ctx, Object configRow) {
        HooksConfig parsed = parseConfig(configRow);
        if (parsed == null || parsed.isEmpty()) {
            return null;
        }
        HookRunner hookRunner = runner != null ? runner
                : new HookRunner(ctx.get(ShellService.NAME), workdir);
        SessionService sessionService = sessions != null ? sessions : ctx.get(SessionService.NAME);
        List<Disposable> registrations = new ArrayList<>();
        List<MatcherGroup> preTool = flatten(parsed.groups("PreToolUse"));
        if (!preTool.isEmpty()) {
            registrations.add(ctx.on(ToolEvents.PRE_EXECUTE, (thisArg, args) ->
                    runPreToolUse(hookRunner, sessionService, preTool,
                            (ToolCall) args[0],
                            (java.util.function.Supplier<Object>) args[args.length - 1])));
        }
        List<MatcherGroup> userPrompt = flatten(parsed.groups("UserPromptSubmit"));
        if (!userPrompt.isEmpty()) {
            registrations.add(ctx.on(AgentLoopEvents.USER_SUBMIT, (thisArg, args) ->
                    runUserPromptSubmit(hookRunner, sessionService, userPrompt,
                            (String) args[0], (String) args[1])));
        }
        List<MatcherGroup> stop = flatten(parsed.groups("Stop"));
        if (!stop.isEmpty()) {
            registrations.add(ctx.on(AgentLoopEvents.TURN_CLOSED, (thisArg, args) ->
                    runStop(hookRunner, sessionService, stop, (String) args[0])));
        }
        return Disposables.composite(registrations);
    }

    /** Extracts the config (file path or inline) at construction. */
    HooksPlugin(HooksConfig config, String workdir, SessionService sessions, HookRunner runner) {
        this.config = config;
        this.workdir = workdir;
        this.sessions = sessions;
        this.runner = runner;
    }

    public HooksPlugin() {
        this(null, null, null, null);
    }

    private HooksConfig parseConfig(Object configRow) {
        if (config != null) {
            return config;
        }
        if (!(configRow instanceof Map<?, ?> map)) {
            return null;
        }
        try {
            if (map.get("path") != null) {
                Path file = Path.of(String.valueOf(map.get("path")));
                if (!Files.exists(file)) {
                    // profile rows reference hooks.json by convention; no file
                    // means no hooks (mirrors skill-files' missing-dir skip)
                    return null;
                }
                return HooksConfig.parse(Files.readString(file));
            }
            if (map.get("hooks") instanceof Map<?, ?>) {
                ObjectNode root = MAPPER.createObjectNode();
                root.set("hooks", MAPPER.valueToTree(map.get("hooks")));
                return HooksConfig.parse(root);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("hooks: cannot load config: " + e.getMessage(), e);
        }
        return null;
    }

    // ----- PreToolUse: the blocking gate -----

    private Object runPreToolUse(HookRunner hookRunner, SessionService sessionService,
            List<MatcherGroup> groups, ToolCall call,
            java.util.function.Supplier<Object> next) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("hook_event_name", "PreToolUse");
        payload.put("tool_name", call.name());
        try {
            payload.set("tool_input", MAPPER.readTree(call.arguments()));
        } catch (Exception ignored) {
            payload.putObject("tool_input");
        }
        for (MatcherGroup group : groups) {
            for (HooksConfig.CommandHook hook : group.hooks()) {
                if (!HookMatcher.matches(group.matcher(), call.name())) {
                    continue;
                }
                String handlerId = handlerId();
                long started = System.currentTimeMillis();
                audit(sessionService, SessionEventType.HOOK_INVOKED, Map.of(
                        SessionEvent.FIELD_POINT, "PreToolUse",
                        SessionEvent.FIELD_HANDLER_ID, handlerId));
                HookOutput output = hookRunner.run(hook, payload, dialectEnv());
                audit(sessionService, SessionEventType.HOOK_RESULT, Map.of(
                        SessionEvent.FIELD_HANDLER_ID, handlerId,
                        SessionEvent.FIELD_DECISION, decisionWord(output),
                        SessionEvent.FIELD_DURATION_MS, System.currentTimeMillis() - started));
                if (output.blocks()) {
                    return ToolResult.error("blocked by PreToolUse hook: "
                            + output.blockReason());
                }
            }
        }
        return next.get();
    }

    // ----- UserPromptSubmit: reject or augment before durability -----

    private String runUserPromptSubmit(HookRunner hookRunner, SessionService sessionService,
            List<MatcherGroup> groups, String sessionId, String userText) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("hook_event_name", "UserPromptSubmit");
        payload.put("prompt", userText);
        StringBuilder context = new StringBuilder();
        for (MatcherGroup group : groups) {
            for (HooksConfig.CommandHook hook : group.hooks()) {
                if (!HookMatcher.matches(group.matcher(), userText)) {
                    continue;
                }
                String handlerId = handlerId();
                long started = System.currentTimeMillis();
                audit(sessionService, SessionEventType.HOOK_INVOKED, Map.of(
                        SessionEvent.FIELD_POINT, "UserPromptSubmit",
                        SessionEvent.FIELD_HANDLER_ID, handlerId));
                HookOutput output = hookRunner.run(hook, payload, dialectEnv());
                audit(sessionService, SessionEventType.HOOK_RESULT, Map.of(
                        SessionEvent.FIELD_HANDLER_ID, handlerId,
                        SessionEvent.FIELD_DECISION, decisionWord(output),
                        SessionEvent.FIELD_DURATION_MS, System.currentTimeMillis() - started));
                if (output.blocks()) {
                    // the submission was never logged: failing loudly is safe
                    throw new IllegalStateException(
                            "rejected by UserPromptSubmit hook: " + output.blockReason());
                }
                String hookContext = output.additionalContext();
                if (hookContext != null && !hookContext.isBlank()) {
                    if (context.length() > 0) {
                        context.append('\n');
                    }
                    context.append(hookContext);
                }
            }
        }
        // stdout context rides the durable text itself: what the model sees
        // is exactly one logged user message
        return context.length() == 0 ? userText : userText + "\n\n" + context;
    }

    // ----- Stop: post-turn context, never blocking -----

    private Object runStop(HookRunner hookRunner, SessionService sessionService,
            List<MatcherGroup> groups, String sessionId) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("hook_event_name", "Stop");
        for (MatcherGroup group : groups) {
            for (HooksConfig.CommandHook hook : group.hooks()) {
                if (!HookMatcher.matches(group.matcher(), sessionId)) {
                    continue;
                }
                String handlerId = handlerId();
                long started = System.currentTimeMillis();
                audit(sessionService, SessionEventType.HOOK_INVOKED, Map.of(
                        SessionEvent.FIELD_POINT, "Stop",
                        SessionEvent.FIELD_HANDLER_ID, handlerId));
                HookOutput output = hookRunner.run(hook, payload, dialectEnv());
                audit(sessionService, SessionEventType.HOOK_RESULT, Map.of(
                        SessionEvent.FIELD_HANDLER_ID, handlerId,
                        SessionEvent.FIELD_DECISION, decisionWord(output),
                        SessionEvent.FIELD_DURATION_MS, System.currentTimeMillis() - started));
                if (sessionService != null
                        && output.additionalContext() != null
                        && !output.additionalContext().isBlank()) {
                    sessionService.append(sessionId, SessionEventType.CONTEXT_NOTE,
                            Map.of(SessionEvent.FIELD_CONTENT, output.additionalContext()));
                }
            }
        }
        return null;
    }

    // ----- plumbing -----

    private static List<MatcherGroup> flatten(List<HooksConfig.MatcherGroup> groups) {
        List<MatcherGroup> flattened = new ArrayList<>();
        for (HooksConfig.MatcherGroup group : groups) {
            flattened.add(new MatcherGroup(group.matcher(), group.hooks()));
        }
        return List.copyOf(flattened);
    }

    private static java.util.Map<String, String> dialectEnv() {
        Map<String, String> env = new HashMap<>();
        String sessionId = InteractionContext.sessionId();
        if (sessionId != null) {
            env.put("MAJO_SESSION_ID", sessionId);
        }
        return env;
    }

    private static String handlerId() {
        return "hook-" + HANDLER_IDS.incrementAndGet();
    }

    private static String decisionWord(HookOutput output) {
        if (output.exitCode == null) {
            return "error";
        }
        if (output.blocks()) {
            return "blocked";
        }
        if (output.allows()) {
            return "allowed";
        }
        if (output.decision == HookOutput.Decision.ASK) {
            return "asked";
        }
        return "passed";
    }

    private void audit(SessionService sessionService, SessionEventType type,
            Map<String, Object> fields) {
        if (sessionService == null) {
            return;
        }
        String sessionId = InteractionContext.sessionId();
        if (sessionId == null) {
            return;
        }
        try {
            sessionService.append(sessionId, type, fields);
        } catch (RuntimeException ignored) {
            // hook audit is advisory; never break the intercepted action
        }
    }

    /** The matcher group view used by the bridges (mirrors the config record). */
    record MatcherGroup(String matcher, List<HooksConfig.CommandHook> hooks) {
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(ShellService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
