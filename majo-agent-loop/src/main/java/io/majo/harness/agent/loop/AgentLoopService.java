package io.majo.harness.agent.loop;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.majo.harness.interaction.InteractionContext;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p><b>Inbox (dsh next-turn / next-step analog).</b> Each session carries an
 * {@link AgentInbox}: {@link #followup} queues a next turn (jobs/schedule
 * notices), waking an idle loop through a virtual-thread driver; {@link
 * #steer} splices input into the running turn at the next step boundary (or
 * wakes its own turn when idle); {@link #inject} splices a {@link
 * SessionEventType#CONTEXT_NOTE} without ever waking. Chained turns converge
 * inside the caller's turn hold, so per-session serialization is preserved.
 *
 * <p>Config: {@code {systemPrompt: <text>, maxSteps: <n>}}. A turn that fails
 * to converge within {@code maxSteps} fails loudly.
 */
public final class AgentLoopService extends Service {

    public static final String NAME = "agentLoop";
    private static final Logger LOG = LoggerFactory.getLogger(AgentLoopService.class);

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
    /** Drives inbox wakes for turns nobody is blocking on. */
    private final java.util.concurrent.ExecutorService turnDriver = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    /** Serializes turns per session (web holds its own lock around runTurn). */
    private final Map<String, Object> turnMutexes = new ConcurrentHashMap<>();
    private final Map<String, AgentInbox> inboxes = new ConcurrentHashMap<>();
    /** Latch: a driver thread is armed for this session (lost-wake guard). */
    private final Map<String, AtomicBoolean> driving = new ConcurrentHashMap<>();
    /** Whether a turn body is currently executing (steer routing decision). */
    private final Map<String, AtomicBoolean> inTurn = new ConcurrentHashMap<>();

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

    // ----- inbox API -----

    /**
     * Queues a next turn for {@code sessionId}: when the loop is idle a
     * driver thread runs it; when a turn is executing it chains right after
     * (inside the current turn hold). Returns immediately.
     */
    public void followup(String sessionId, String text) {
        inbox(sessionId).offerTurnStart(text);
        requestDrive(sessionId);
    }

    /**
     * Steers the running turn: the text lands as a durable user message at the
     * next step boundary. When no turn is executing it becomes a turn of its
     * own (wake semantics).
     */
    public void steer(String sessionId, String text) {
        if (inTurn(sessionId).get()) {
            inbox(sessionId).offerNote(new AgentInbox.Note(AgentInbox.Kind.STEER, text));
        } else {
            followup(sessionId, text);
        }
    }

    /**
     * Splices context into {@code sessionId} without waking the loop: the note
     * is model-visible from the next step boundary (or next turn opening) but
     * never starts a turn; with no later turn it simply waits.
     */
    public void inject(String sessionId, String text) {
        inbox(sessionId).offerNote(new AgentInbox.Note(AgentInbox.Kind.INJECT, text));
    }

    /** Inbox depth for the session (turn starters + undelivered notes). */
    public int queuedCount(String sessionId) {
        AgentInbox inbox = inboxes.get(sessionId);
        return inbox == null ? 0 : inbox.size();
    }

    // ----- turn entry points -----

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
     * defaults). Child delegations ride through these arguments. Inbox entries
     * queued while this turn ran converge before the lock is released.
     */
    public String runTurn(String sessionId, String userText,
            java.util.function.Consumer<String> textSink, String modelOverride,
            String systemPromptOverride) {
        String prompt = systemPromptOverride == null || systemPromptOverride.isBlank()
                ? systemPrompt
                : systemPromptOverride;
        synchronized (turnMutex(sessionId)) {
            String answer = runSingleTurn(sessionId, userText, textSink, modelOverride, prompt);
            converge(sessionId);
            return answer;
        }
    }

    // ----- turn mechanics -----

    /** One durable turn: open, deliver opening notes, step to convergence, close.
     * The turn binds its session so the approval gate can persist the durable
     * ask/decision audit pair into this session's log. */
    private String runSingleTurn(String sessionId, String userText,
            java.util.function.Consumer<String> textSink, String modelOverride, String prompt) {
        String effectivePrompt = prompt == null || prompt.isBlank() ? systemPrompt : prompt;
        AtomicBoolean busy = inTurn(sessionId);
        busy.set(true);
        try {
            return InteractionContext.runSession(sessionId,
                    () -> runSingleTurnBound(sessionId, userText, textSink, modelOverride, effectivePrompt));
        } finally {
            busy.set(false);
        }
    }

    private String runSingleTurnBound(String sessionId, String userText,
            java.util.function.Consumer<String> textSink, String modelOverride, String effectivePrompt) {
        sessions.append(sessionId, SessionEventType.TURN_START, Map.of());
        // notes queued while nobody was driving land before the user message
        deliverQueuedNotes(sessionId);
        sessions.append(sessionId, SessionEventType.USER_MESSAGE,
                Map.of(SessionEvent.FIELD_CONTENT, userText));
        for (int step = 1; ; step++) {
            if (step > maxSteps) {
                throw new IllegalStateException("agent-loop: turn on session \"" + sessionId
                        + "\" exceeded maxSteps=" + maxSteps + " without a final answer");
            }
            if (step > 1) {
                // steering + notes splice in at step boundaries, never mid-request
                deliverQueuedNotes(sessionId);
            }
            ChatRequest request = new ChatRequest(buildMessages(sessionId, effectivePrompt),
                    tools.specs(), modelOverride);
            // log the request composition before it reaches the model so the
            // header (model, system prompt, offered tool names) is durable
            // even when the completion itself fails
            sessions.append(sessionId, SessionEventType.REQUEST_HEADER, Map.of(
                    SessionEvent.FIELD_MODEL, llm.modelNameOf(request),
                    SessionEvent.FIELD_SYSTEM_PROMPT, effectivePrompt,
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

    /** Appends queued notes: steer as user input, inject as a context note. */
    private void deliverQueuedNotes(String sessionId) {
        for (AgentInbox.Note note : inbox(sessionId).drainNotes()) {
            sessions.append(sessionId,
                    note.kind() == AgentInbox.Kind.STEER
                            ? SessionEventType.USER_MESSAGE
                            : SessionEventType.CONTEXT_NOTE,
                    Map.of(SessionEvent.FIELD_CONTENT, note.text()));
        }
    }

    /**
     * Drains turn-starting entries after a turn, inside the caller's lock:
     * followups chain as turns; steer notes that raced in after the last step
     * boundary wake their own turn; inject notes keep waiting (never wake).
     */
    private void converge(String sessionId) {
        AgentInbox inbox = inbox(sessionId);
        while (true) {
            String next = inbox.pollTurnStart();
            if (next != null) {
                runSingleTurn(sessionId, next, null, null, null);
                continue;
            }
            boolean wake = false;
            for (AgentInbox.Note note : inbox.drainNotes()) {
                if (note.kind() == AgentInbox.Kind.STEER) {
                    inbox.offerTurnStart(note.text());
                    wake = true;
                } else {
                    inbox.offerNote(note); // INJECT: stays queued without waking
                }
            }
            if (!wake) {
                return;
            }
        }
    }

    /**
     * Arms (or piggybacks on) the virtual-thread driver for queued turns. The
     * driver polls only while holding the session turn mutex, so it never
     * steals entries from a caller's inline converge. On quiescence it drops
     * the latch, re-scans once under the mutex (closing the offer/scan race),
     * and re-arms itself when work appeared — a fresh offer's CAS may also
     * have taken over, in which case this driver simply exits.
     */
    private void requestDrive(String sessionId) {
        AtomicBoolean latch = driving.computeIfAbsent(sessionId, ignored -> new AtomicBoolean());
        if (!latch.compareAndSet(false, true)) {
            return; // a driver is armed; it re-scans before exiting
        }
        turnDriver.execute(() -> {
            Object mutex = turnMutex(sessionId);
            while (true) {
                String next;
                synchronized (mutex) {
                    next = inbox(sessionId).pollTurnStart();
                    if (next != null) {
                        try {
                            runSingleTurn(sessionId, next, null, null, null);
                            converge(sessionId);
                        } catch (RuntimeException failure) {
                            // a driver turn has no caller to fail: log and
                            // keep draining so one bad notice cannot wedge
                            // the queue
                            LOG.error("agent-loop: inbox-driven turn on session \"{}\" failed",
                                    sessionId, failure);
                        }
                    }
                }
                if (next == null) {
                    latch.set(false);
                    synchronized (mutex) {
                        if (!inbox(sessionId).hasTurnWork()) {
                            return;
                        }
                    }
                    if (!latch.compareAndSet(false, true)) {
                        return; // a fresh offer armed another driver
                    }
                }
            }
        });
    }

    private AgentInbox inbox(String sessionId) {
        return inboxes.computeIfAbsent(sessionId, ignored -> new AgentInbox());
    }

    private Object turnMutex(String sessionId) {
        return turnMutexes.computeIfAbsent(sessionId, ignored -> new Object());
    }

    private AtomicBoolean inTurn(String sessionId) {
        return inTurn.computeIfAbsent(sessionId, ignored -> new AtomicBoolean());
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
