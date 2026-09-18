package io.majo.harness.workflow;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import io.majo.harness.subagent.SubagentService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The workflow runtime ({@code ctx.workflow}, roadmap-0.5 / design
 * docs/workflow-design.md): runs a named definition's steps in order, each
 * as a scoped child delegation (dsh-style child-session execution — the
 * requesting session only carries the {@code WORKFLOW_*} bookkeeping and a
 * final summary). Data flows exclusively through explicit
 * {@code {{steps.<id>.output}}} interpolation.
 *
 * <p>Phase B: consecutive steps marked {@code parallel: true} run
 * concurrently (virtual threads); every run leaves a {@link RunRecord} —
 * {@code /workflow status} lists them, and a failed run can
 * {@link #resume} in-process from its first failed step (recorded outputs
 * and arguments are replayed; a host crash still restarts fresh — durable
 * resume stays future work).
 */
public final class WorkflowService extends Service {

    public static final String NAME = "workflow";
    private static final int MAX_RECORDS = 50;

    static final Logger LOG = LoggerFactory.getLogger(WorkflowService.class);

    /** Observable run state for {@code /workflow status} and resume. */
    public static final class RunRecord {
        public final String runId;
        public final String sessionId;
        public final String name;
        public final long startedAt;
        public final Map<String, String> args = new ConcurrentHashMap<>();
        public final Map<String, String> outputs = new ConcurrentHashMap<>();
        public final Map<String, String> stepStatus = new ConcurrentHashMap<>();
        public volatile String status = "running";
        public volatile long durationMs;

        RunRecord(String runId, String sessionId, String name) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.name = name;
            this.startedAt = System.currentTimeMillis();
        }
    }

    private final SessionService sessions;
    private final SubagentService subagent;
    private final Map<String, WorkflowDefinition> definitions;
    private final Deque<RunRecord> records = new ArrayDeque<>();

    public WorkflowService(Context ctx, SessionService sessions, SubagentService subagent,
            Path directory) throws IOException {
        super(ctx, NAME);
        this.sessions = sessions;
        this.subagent = subagent;
        this.definitions = WorkflowDefinition.loadDir(directory,
                error -> LOG.error("workflow: {}", error));
    }

    /** Known workflow names in mount order. */
    public List<String> names() {
        return List.copyOf(definitions.keySet());
    }

    /** One definition, or {@code null}. */
    public WorkflowDefinition definition(String name) {
        return definitions.get(name);
    }

    /** Recent runs, newest first (bounded). */
    public synchronized List<RunRecord> records() {
        return List.copyOf(records);
    }

    /** One run record by id, or {@code null}. */
    public synchronized RunRecord record(String runId) {
        for (RunRecord record : records) {
            if (record.runId.equals(runId)) {
                return record;
            }
        }
        return null;
    }

    /**
     * Tool-entry variant: resolves the requesting session from the interaction
     * context (the caller runs inside a model turn).
     */
    public String runForCaller(String name, Map<String, String> args) {
        String sessionId = InteractionContext.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException(
                    "workflow_run: no open session (the caller must run inside a turn)");
        }
        return run(sessionId, name, args);
    }

    /**
     * Runs {@code name} with {@code args}, emitting {@code WORKFLOW_START} /
     * {@code WORKFLOW_STEP} / {@code WORKFLOW_END} into the requesting
     * session, and returns the last step's output as the run summary. A step
     * failure throws when the definition says {@code abort} (default) and is
     * swallowed (output {@code ""}) on {@code continue}.
     */
    public String run(String requestingSessionId, String name, Map<String, String> args) {
        WorkflowDefinition definition = requireDefinition(name);
        RunRecord record = newRecord(requestingSessionId, name);
        record.args.putAll(args == null ? Map.of() : args);
        emitStart(definition, record, "");
        String failure = executeSteps(definition, record);
        emitEnd(record, definition, failure);
        finish(record, failure);
        if (failure != null) {
            throw new IllegalStateException("workflow \"" + name + "\": " + failure);
        }
        return summaryOf(definition, record);
    }

    /**
     * Resumes a failed run in-process: recorded outputs and arguments are
     * replayed, succeeded steps are skipped, and execution restarts at the
     * first non-ok step (same run id, fresh bookkeeping events).
     */
    public String resume(String runId) {
        RunRecord record = record(runId);
        if (record == null) {
            throw new IllegalArgumentException("workflow: unknown run \"" + runId + "\"");
        }
        if (!"failed".equals(record.status)) {
            throw new IllegalArgumentException("workflow: run \"" + runId + "\" is "
                    + record.status + "; nothing to resume");
        }
        WorkflowDefinition definition = requireDefinition(record.name);
        emitStart(definition, record, " (resume)");
        String failure = executeSteps(definition, record);
        emitEnd(record, definition, failure);
        finish(record, failure);
        if (failure != null) {
            throw new IllegalStateException("workflow \"" + record.name + "\": " + failure);
        }
        return summaryOf(definition, record);
    }

    /** Ordered execution with consecutive {@code parallel: true} groups fanned out. */
    private String executeSteps(WorkflowDefinition definition, RunRecord record) {
        List<WorkflowDefinition.Step> steps = definition.steps();
        int index = 0;
        while (index < steps.size()) {
            WorkflowDefinition.Step step = steps.get(index);
            if ("ok".equals(record.stepStatus.get(step.id()))) {
                index++;
                continue;
            }
            if (!step.parallel()) {
                boolean ok = runStep(definition, record, step);
                if (!ok && definition.onFailure().equals(WorkflowDefinition.ON_FAILURE_ABORT)) {
                    return "step \"" + step.id() + "\" "
                            + record.stepStatus.get(step.id());
                }
                index++;
                continue;
            }
            List<WorkflowDefinition.Step> group = new ArrayList<>();
            while (index < steps.size()
                    && steps.get(index).parallel()
                    && !"ok".equals(record.stepStatus.get(steps.get(index).id()))) {
                group.add(steps.get(index));
                index++;
            }
            try (ExecutorService fanout = Executors.newVirtualThreadPerTaskExecutor()) {
                List<java.util.concurrent.Future<?>> joins = new ArrayList<>();
                for (WorkflowDefinition.Step member : group) {
                    joins.add(fanout.submit(() -> runStep(definition, record, member)));
                }
                for (java.util.concurrent.Future<?> join : joins) {
                    try {
                        join.get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("workflow interrupted", e);
                    } catch (java.util.concurrent.ExecutionException e) {
                        LOG.error("workflow \"{}\" parallel step crashed",
                                definition.name(), e.getCause());
                    }
                }
            }
            for (WorkflowDefinition.Step member : group) {
                if (!"ok".equals(record.stepStatus.get(member.id()))
                        && definition.onFailure().equals(WorkflowDefinition.ON_FAILURE_ABORT)) {
                    return "step \"" + member.id() + "\" "
                            + record.stepStatus.get(member.id());
                }
            }
        }
        return null;
    }

    /** Runs one step: interpolate, delegate, emit its WORKFLOW_STEP event. */
    private boolean runStep(WorkflowDefinition definition, RunRecord record,
            WorkflowDefinition.Step step) {
        long stepStart = System.currentTimeMillis();
        String status;
        try {
            String prompt = WorkflowDefinition.interpolate(
                    step.prompt(), record.args, record.outputs);
            var outcome = subagent.delegateSpec(prompt, specFor(step));
            record.outputs.put(step.id(), outcome.answer());
            record.stepStatus.put(step.id(), "ok");
            status = "ok";
        } catch (RuntimeException e) {
            status = "failed: " + e.getMessage();
            record.stepStatus.put(step.id(), status);
            LOG.error("workflow \"{}\" step \"{}\" failed", definition.name(), step.id(), e);
        }
        sessions.append(record.sessionId, SessionEventType.WORKFLOW_STEP,
                Map.of(SessionEvent.FIELD_STEP_ID, step.id(),
                        SessionEvent.FIELD_STATUS, status,
                        SessionEvent.FIELD_DURATION_MS,
                        System.currentTimeMillis() - stepStart,
                        SessionEvent.FIELD_RUN_ID, record.runId));
        return status.equals("ok");
    }

    private SubagentService.AgentSpec specFor(WorkflowDefinition.Step step) {
        if (step.kind().equals("delegate")) {
            return new SubagentService.AgentSpec(step.model(), step.systemPrompt(),
                    step.maxSteps(), step.autoApprove(), step.allowedTools());
        }
        return new SubagentService.AgentSpec(null, null, null);
    }

    private WorkflowDefinition requireDefinition(String name) {
        WorkflowDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("unknown workflow \"" + name
                    + "\"; known: " + definitions.keySet());
        }
        return definition;
    }

    private RunRecord newRecord(String sessionId, String name) {
        RunRecord record = new RunRecord(
                UUID.randomUUID().toString().substring(0, 8), sessionId, name);
        synchronized (records) {
            records.addFirst(record);
            while (records.size() > MAX_RECORDS) {
                records.removeLast();
            }
        }
        return record;
    }

    private void emitStart(WorkflowDefinition definition, RunRecord record, String suffix) {
        sessions.append(record.sessionId, SessionEventType.WORKFLOW_START,
                Map.of(SessionEvent.FIELD_CONTENT, definition.name() + suffix,
                        SessionEvent.FIELD_RUN_ID, record.runId));
    }

    private void emitEnd(RunRecord record, WorkflowDefinition definition, String failure) {
        sessions.append(record.sessionId, SessionEventType.WORKFLOW_END,
                Map.of(SessionEvent.FIELD_CONTENT,
                        failure == null ? "completed" : failure,
                        SessionEvent.FIELD_RUN_ID, record.runId,
                        SessionEvent.FIELD_DURATION_MS,
                        System.currentTimeMillis() - record.startedAt));
    }

    private void finish(RunRecord record, String failure) {
        record.status = failure == null ? "completed" : "failed";
        record.durationMs = System.currentTimeMillis() - record.startedAt;
    }

    /** The run summary: the last definition step that produced an output. */
    private static String summaryOf(WorkflowDefinition definition, RunRecord record) {
        List<WorkflowDefinition.Step> steps = definition.steps();
        for (int index = steps.size() - 1; index >= 0; index--) {
            String output = record.outputs.get(steps.get(index).id());
            if (output != null) {
                return output;
            }
        }
        return "";
    }
}
