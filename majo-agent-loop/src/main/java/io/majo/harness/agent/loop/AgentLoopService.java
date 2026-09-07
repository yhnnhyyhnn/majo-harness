package io.majo.harness.agent.loop;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.majo.harness.llm.ChatMessage;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.ChatResponse;
import io.majo.harness.llm.LLMService;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The default turn driver ({@code ctx.agentLoop}), mirroring the dsh
 * agent-loop: one {@link #runTurn} opens a durable turn, appends the user
 * message, then steps — each step derives model history from the session log,
 * asks the model, logs the assistant round verbatim, and executes any tool
 * calls it requested — until the model answers without tools, then closes the
 * turn.
 *
 * <p>Every step boundary is observable through {@code session/event} and the
 * {@code llm/*} events; nothing model-visible bypasses the log.
 *
 * <p>Config: {@code {systemPrompt: <text>, maxSteps: <n>}}. A turn that fails
 * to converge within {@code maxSteps} fails loudly.
 */
public final class AgentLoopService extends Service {

    public static final String NAME = "agentLoop";

    public static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful agent harness.";
    public static final int DEFAULT_MAX_STEPS = 8;

    private final SessionService sessions;
    private final ToolRegistry tools;
    private final LLMService llm;
    private final String systemPrompt;
    private final int maxSteps;
    private final boolean parallelDelegates;
    private final io.majo.harness.credentials.CredentialsService credentials;
    private final java.util.concurrent.ExecutorService delegates = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    public AgentLoopService(Context ctx, Object config) {
        super(ctx, NAME);
        this.sessions = require(ctx, SessionService.NAME);
        this.tools = require(ctx, ToolRegistry.NAME);
        this.llm = require(ctx, LLMService.NAME);
        if (config instanceof Map<?, ?> map) {
            Object prompt = map.get("systemPrompt");
            this.systemPrompt = prompt == null ? DEFAULT_SYSTEM_PROMPT : String.valueOf(prompt);
            Object steps = map.get("maxSteps");
            this.maxSteps = steps == null ? DEFAULT_MAX_STEPS : Integer.parseInt(String.valueOf(steps));
            Object parallel = map.get("parallelDelegates");
            this.parallelDelegates = parallel == null ? false
                    : parallel instanceof Boolean bool ? bool
                            : Boolean.parseBoolean(String.valueOf(parallel));
        } else {
            this.systemPrompt = DEFAULT_SYSTEM_PROMPT;
            this.maxSteps = DEFAULT_MAX_STEPS;
            this.parallelDelegates = false;
        }
        if (maxSteps < 1) {
            throw new IllegalArgumentException("agent-loop: maxSteps must be >= 1, got " + maxSteps);
        }
        this.credentials = ctx.get(io.majo.harness.credentials.CredentialsService.NAME);
    }

    /** Redacts resolved credential values from model-visible text before it is stored. */
    private String safe(String text) {
        return credentials == null ? text : credentials.redact(text);
    }

    private static <T> T require(Context ctx, String name) {
        T value = ctx.get(name);
        if (value == null) {
            throw new IllegalStateException(
                    "agent-loop: service \"" + name + "\" unavailable — declare it as an injection");
        }
        return value;
    }

    /**
     * Runs one turn (see {@link #runTurn(String, String, java.util.function.Consumer)})
     * without a text sink.
     */
    public String runTurn(String sessionId, String userText) {
        return runTurn(sessionId, userText, null, null);
    }

    /**
     * Runs one turn: {@code userText} is logged and answered, returning the
     * final assistant text. When {@code textSink} is supplied, answer deltas
     * flow through it (real tokens for streaming providers, one burst for
     * plain ones); without a sink the completion is not forced to stream, so
     * non-UI callers keep the plain path.
     */
    public String runTurn(String sessionId, String userText, java.util.function.Consumer<String> textSink) {
        return runTurn(sessionId, userText, textSink, null);
    }

    /**
     * Like {@link #runTurn(String, String, java.util.function.Consumer)} but
     * with an explicit model name for this turn ({@code null} = the service
     * default). Per-session overrides ride through this argument.
     */
    public String runTurn(String sessionId, String userText,
            java.util.function.Consumer<String> textSink, String modelOverride) {
        return runTurn(sessionId, userText, textSink, modelOverride, null);
    }

    /**
     * Full per-agent configuration for one turn: explicit model name and a
     * system prompt override ({@code null} values fall back to the service
     * defaults). Child delegations ride through these arguments.
     */
    public String runTurn(String sessionId, String userText,
            java.util.function.Consumer<String> textSink, String modelOverride,
            String systemPromptOverride) {
        String prompt = systemPromptOverride == null || systemPromptOverride.isBlank()
                ? systemPrompt
                : systemPromptOverride;
        sessions.append(sessionId, SessionEventType.TURN_START, Map.of());
        sessions.append(sessionId, SessionEventType.USER_MESSAGE,
                Map.of(SessionEvent.FIELD_CONTENT, userText));
        for (int step = 1; ; step++) {
            if (step > maxSteps) {
                throw new IllegalStateException("agent-loop: turn on session \"" + sessionId
                        + "\" exceeded maxSteps=" + maxSteps + " without a final answer");
            }
            ChatRequest request = new ChatRequest(buildMessages(sessionId, prompt),
                    tools.specs(), modelOverride);
            // log the request composition before it reaches the model so the
            // header (model, system prompt, offered tool names) is durable
            // even when the completion itself fails
            sessions.append(sessionId, SessionEventType.REQUEST_HEADER, Map.of(
                    SessionEvent.FIELD_MODEL, llm.modelNameOf(request),
                    SessionEvent.FIELD_SYSTEM_PROMPT, prompt,
                    SessionEvent.FIELD_TOOL_NAMES,
                    request.tools().stream().map(ToolSpec::name).toList()));
            ChatResponse response = textSink == null
                    ? llm.complete(request)
                    : llm.completeStream(request, textSink);
            appendAssistantRound(sessionId, response);
            if (!response.isToolRound()) {
                break;
            }
            executeTools(sessionId, response.toolCalls());
        }
        sessions.append(sessionId, SessionEventType.TURN_END, Map.of());
        return lastFinalText(sessions.events(sessionId));
    }

    /** Executes one tool round: delegate_task calls run concurrently when
     * {@code parallelDelegates} is enabled; results append in call order. */
    private void executeTools(String sessionId, java.util.List<ToolCall> calls) {
        java.util.List<ToolResult> results;
        if (parallelDelegates
                && calls.stream().anyMatch(call -> call.name().equals("delegate_task"))) {
            java.util.List<java.util.concurrent.CompletableFuture<ToolResult>> futures =
                    calls.stream()
                            .map(call -> call.name().equals("delegate_task")
                                    ? java.util.concurrent.CompletableFuture.supplyAsync(
                                            () -> tools.execute(call), delegates)
                                    : java.util.concurrent.CompletableFuture.completedFuture(
                                            tools.execute(call)))
                            .toList();
            results = futures.stream()
                    .map(future -> {
                        try {
                            return future.join();
                        } catch (java.util.concurrent.CompletionException e) {
                            Throwable cause = e.getCause();
                            if (cause instanceof RuntimeException runtime) {
                                throw runtime;
                            }
                            throw e;
                        }
                    })
                    .toList();
        } else {
            results = new java.util.ArrayList<>();
            for (ToolCall call : calls) {
                results.add(tools.execute(call));
            }
        }
        for (int index = 0; index < calls.size(); index++) {
            appendToolResult(sessionId, calls.get(index), results.get(index));
        }
    }

    private void appendToolResult(String sessionId, ToolCall call, ToolResult result) {
        java.util.Map<String, Object> fields = new java.util.HashMap<>();
        fields.put(SessionEvent.FIELD_TOOL_CALL_ID, call.id());
        fields.put(SessionEvent.FIELD_TOOL_NAME, call.name());
        fields.put(SessionEvent.FIELD_OK, result.ok());
        fields.put(SessionEvent.FIELD_CONTENT, safe(result.visibleText()));
        if (result.data() != null) {
            fields.put(SessionEvent.FIELD_DATA, safeValue(result.data()));
        }
        sessions.append(sessionId, SessionEventType.TOOL_RESULT, fields);
    }

    /** Recursively redacts credential values inside structured tool data. */
    private Object safeValue(Object value) {
        if (value instanceof String text) {
            return safe(text);
        }
        if (value instanceof Map<?, ?> map) {
            java.util.Map<String, Object> cleaned = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                cleaned.put(String.valueOf(entry.getKey()), safeValue(entry.getValue()));
            }
            return cleaned;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::safeValue).toList();
        }
        return value;
    }

    private List<ChatMessage> buildMessages(String sessionId, String prompt) {
        List<ChatMessage> history = MessageDeriver.derive(sessions.events(sessionId));
        List<ChatMessage> messages = new ArrayList<>(history.size() + 1);
        messages.add(ChatMessage.system(prompt));
        messages.addAll(history);
        return List.copyOf(messages);
    }

    /** Logs the assistant round exactly as it was model-visible. */
    private void appendAssistantRound(String sessionId, ChatResponse response) {
        Map<String, Object> fields = new HashMap<>();
        if (response.content() != null) {
            fields.put(SessionEvent.FIELD_CONTENT, safe(response.content()));
        }
        if (response.isToolRound()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCall call : response.toolCalls()) {
                calls.add(Map.of(
                        SessionEvent.FIELD_TOOL_CALL_ID, call.id(),
                        SessionEvent.FIELD_TOOL_NAME, call.name(),
                        SessionEvent.FIELD_ARGUMENTS, call.arguments()));
            }
            fields.put(SessionEvent.FIELD_TOOL_CALLS, calls);
        }
        sessions.append(sessionId, SessionEventType.ASSISTANT_MESSAGE, fields);
    }

    /** The last assistant event text: the model's final answer. */
    private static String lastFinalText(List<SessionEvent> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            SessionEvent event = events.get(i);
            if (event.type() == SessionEventType.ASSISTANT_MESSAGE) {
                Object calls = event.fields().get(SessionEvent.FIELD_TOOL_CALLS);
                if (!(calls instanceof List<?> list) || list.isEmpty()) {
                    return event.content();
                }
            }
        }
        return null;
    }
}
