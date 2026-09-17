package io.majo.harness.jobs;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.agent.loop.AgentLoopService;
import io.majo.harness.sandbox.SandboxService;
import io.majo.harness.shell.ShellFamily;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.util.Disposables;
import java.util.HashMap;
import java.util.Map;

/**
 * Mounts the background jobs capability (dsh jobs): the per-session registry,
 * the {@code run_background} producer and the {@code job_output}/{@code
 * job_list}/{@code job_kill} tools, and the completion wiring — a finished
 * job becomes an inbox follow-up turn ("job shell-1 completed (exit 0): …"),
 * so an idle harness wakes and a busy one delivers on its next turn.
 *
 * <p>Config: {@code {shell: <family>, maxPerSession: <n>, confine: <bool>}}.
 */
public final class JobsPlugin implements Plugin {

    public static final String NAME = "jobs";
    public static final int DEFAULT_MAX_PER_SESSION = 10;

    @Override
    public Object apply(Context ctx, Object config) {
        Map<?, ?> map = config instanceof Map<?, ?> m ? m : Map.of();
        ShellFamily family = ShellFamily.ofConfig(map.get("shell"));
        int maxPerSession = DEFAULT_MAX_PER_SESSION;
        if (map.get("maxPerSession") instanceof Number number && number.intValue() >= 1) {
            maxPerSession = number.intValue();
        }
        SandboxService sandbox = null;
        if (Boolean.TRUE.equals(map.get("confine"))) {
            sandbox = ctx.get(SandboxService.NAME);
            if (sandbox == null) {
                throw new IllegalArgumentException(
                        "jobs: confine requires the sandbox plugin to be mounted (row \"sandbox\")");
            }
        }
        JobsService jobs = new JobsService(ctx, family.launcher(), maxPerSession, sandbox);
        AgentLoopService loop = ctx.get(AgentLoopService.NAME);
        if (loop != null) {
            jobs.onFinished((sessionId, job) -> loop.followup(sessionId,
                    "background job " + job.id + " "
                            + job.state.name().toLowerCase() + " (exit " + job.exitCode + ")"
                            + (job.output.isBlank() ? "" : ":\n" + job.output)));
        }
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        Disposable producer = tools.register(new RunBackgroundTool(jobs));
        Disposable output = tools.register(new JobOutputTool(jobs));
        Disposable list = tools.register(new JobListTool(jobs));
        Disposable kill = tools.register(new JobKillTool(jobs));
        return Disposables.composite(producer, output, list, kill);
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
