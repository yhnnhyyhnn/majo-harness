package io.majo.harness.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolsPlugin;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Background job semantics: spawn-with-id, output capture, kill, the
 * per-session cap, and the completion hook that the plugin wires to the
 * inbox. Scripts are picked per platform (the shell family follows the OS
 * default, PowerShell on Windows, bash elsewhere).
 */
class JobsTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static Context harness(int maxPerSession) {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new JobsPlugin(), Map.of("maxPerSession", maxPerSession)).await().join();
        return ctx;
    }

    private static ToolResult tool(Context ctx, String name, String rawScript) {
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        String scriptJson = com.fasterxml.jackson.databind.node.TextNode.valueOf(rawScript).toString();
        String arguments = switch (name) {
            case "run_background" -> "{\"script\":" + scriptJson + "}";
            case "job_kill", "job_output" -> "{\"id\":\"" + rawScript + "\"}";
            default -> "{}";
        };
        return InteractionContext.runSession("s1", () -> tools.execute(ToolCall.of(name, arguments)));
    }

    private static void awaitTerminated(JobsService.Job job, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (job.state == JobsService.State.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
    }

    @Test
    void backgroundScriptRunsCapturesOutputAndFiresTheNotice() throws Exception {
        Context ctx = harness(3);
        SessionService sessions = ctx.get(SessionService.NAME);
        sessions.createSession();
        JobsService jobs = ctx.get(JobsService.NAME);
        List<String> notices = new CopyOnWriteArrayList<>();
        jobs.onFinished((sessionId, job) -> notices.add(sessionId + " " + job.id
                + " " + job.state.name().toLowerCase() + " " + job.output));

        ToolResult started = tool(ctx, "run_background", "echo job-marker-42");
        assertThat(started.ok()).as(started.visibleText()).isTrue();
        String jobId = String.valueOf(started.data().get("jobId"));
        assertThat(jobId).isEqualTo("shell-1");

        JobsService.Job job = jobs.get("s1", jobId);
        awaitTerminated(job, 10_000);
        assertThat(job.state).isEqualTo(JobsService.State.COMPLETED);
        assertThat(job.output).contains("job-marker-42");
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0)).contains("s1", "shell-1", "completed", "job-marker-42");

        // job_output reads the finished job
        ToolResult output = tool(ctx, "job_output", jobId);
        assertThat(output.ok()).as(output.visibleText()).isTrue();
        assertThat(output.visibleText()).contains("completed", "job-marker-42");
    }

    @Test
    void killStopsARunningJobAndReportsKilled() throws Exception {
        Context ctx = harness(3);
        SessionService sessions = ctx.get(SessionService.NAME);
        sessions.createSession();
        JobsService jobs = ctx.get(JobsService.NAME);
        String slow = WINDOWS ? "Start-Sleep -Seconds 30" : "sleep 30";

        ToolResult started = tool(ctx, "run_background", slow);
        assertThat(started.ok()).isTrue();
        JobsService.Job job = jobs.get("s1", String.valueOf(started.data().get("jobId")));

        ToolResult killed = tool(ctx, "job_kill", job.id);
        assertThat(killed.ok()).isTrue();
        awaitTerminated(job, 10_000);
        assertThat(job.state).isEqualTo(JobsService.State.KILLED);

        // killing again reports nothing-to-kill
        ToolResult again = tool(ctx, "job_kill", job.id);
        assertThat(again.ok()).isFalse();
    }

    @Test
    void perSessionCapAndIsolation() {
        Context ctx = harness(1);
        SessionService sessions = ctx.get(SessionService.NAME);
        sessions.createSession();
        sessions.createSession();
        JobsService jobs = ctx.get(JobsService.NAME);
        String slow = WINDOWS ? "Start-Sleep -Seconds 30" : "sleep 30";

        InteractionContext.runSession("s1", () -> jobs.start("s1", slow));
        // cap: a second concurrent job in the same session fails loud
        assertThatThrownBy(() -> InteractionContext.runSession("s1", () -> jobs.start("s1", slow)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("concurrent cap");
        // isolation: session s2 has its own registry and its own counter
        JobsService.Job s2Job = InteractionContext.runSession("s2", () -> jobs.start("s2", slow));
        assertThat(s2Job.id).isEqualTo("shell-1");
        assertThat(jobs.list("s1")).hasSize(1);
        assertThat(jobs.list("s2")).hasSize(1);
        jobs.kill("s1", "shell-1");
        jobs.kill("s2", "shell-1");
    }

    @Test
    void unboundToolCallsFailAsToolErrors() {
        Context ctx = harness(3);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        ToolResult listed = tools.execute(ToolCall.of("job_list", "{}"));
        assertThat(listed.ok()).isFalse();
        assertThat(listed.visibleText()).contains("no session is bound");
    }
}
