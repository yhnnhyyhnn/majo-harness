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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The workflow runtime ({@code ctx.workflow}, roadmap-0.5 / design
 * docs/workflow-design.md): runs a named definition's steps in order, each
 * as a scoped child delegation (dsh-style child-session execution — the
 * requesting session only carries the {@code WORKFLOW_*} bookkeeping and a
 * final summary). Data flows exclusively through explicit
 * {@code {{steps.<id>.output}}} interpolation.
 */
public final class WorkflowService extends Service {

    public static final String NAME = "workflow";
    public static final String NO_TOOLS = "workflow: step declared allowedTools but the list was empty";

    static final Logger LOG = LoggerFactory.getLogger(WorkflowService.class);

    private final SessionService sessions;
    private final SubagentService subagent;
    private final Map<String, WorkflowDefinition> definitions;

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
        WorkflowDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("unknown workflow \"" + name
                    + "\"; known: " + definitions.keySet());
        }
        Map<String, String> runArgs = args == null ? Map.of() : args;
        String runId = UUID.randomUUID().toString().substring(0, 8);
        long startedAt = System.currentTimeMillis();
        sessions.append(requestingSessionId, SessionEventType.WORKFLOW_START,
                Map.of(SessionEvent.FIELD_CONTENT, name,
                        SessionEvent.FIELD_RUN_ID, runId));

        Map<String, String> outputs = new LinkedHashMap<>();
        String failure = null;
        WorkflowDefinition.Step last = null;
        for (WorkflowDefinition.Step step : definition.steps()) {
            long stepStart = System.currentTimeMillis();
            last = step;
            String status;
            try {
                String prompt = WorkflowDefinition.interpolate(
                        step.prompt(), runArgs, outputs);
                var outcome = subagent.delegateSpec(prompt, specFor(step));
                outputs.put(step.id(), outcome.answer());
                status = "ok";
            } catch (RuntimeException e) {
                status = "failed: " + e.getMessage();
                LOG.error("workflow \"{}\" step \"{}\" failed", name, step.id(), e);
            }
            sessions.append(requestingSessionId, SessionEventType.WORKFLOW_STEP,
                    Map.of(SessionEvent.FIELD_STEP_ID, step.id(),
                            SessionEvent.FIELD_STATUS, status,
                            SessionEvent.FIELD_DURATION_MS,
                            System.currentTimeMillis() - stepStart,
                            SessionEvent.FIELD_RUN_ID, runId));
            if (status.startsWith("failed")) {
                if (definition.onFailure().equals(WorkflowDefinition.ON_FAILURE_ABORT)) {
                    failure = "step \"" + step.id() + "\" " + status;
                    break;
                }
                outputs.put(step.id(), "");
            }
        }

        String finalStatus = failure == null ? "completed" : failure;
        sessions.append(requestingSessionId, SessionEventType.WORKFLOW_END,
                Map.of(SessionEvent.FIELD_CONTENT, finalStatus,
                        SessionEvent.FIELD_RUN_ID, runId,
                        SessionEvent.FIELD_DURATION_MS,
                        System.currentTimeMillis() - startedAt));
        if (failure != null) {
            throw new IllegalStateException("workflow \"" + name + "\": " + failure);
        }
        return last == null ? "" : outputs.getOrDefault(last.id(), "");
    }

    /** Step → scoped child delegation: {@code delegate} honors its overrides, {@code turn} inherits defaults. */
    private static SubagentService.AgentSpec specFor(WorkflowDefinition.Step step) {
        if (step.kind().equals("delegate")) {
            return new SubagentService.AgentSpec(step.model(), step.systemPrompt(),
                    step.maxSteps(), step.autoApprove(), step.allowedTools());
        }
        return new SubagentService.AgentSpec(null, null, null);
    }
}
