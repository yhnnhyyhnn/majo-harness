package io.majo.harness.jobs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The background job registry ({@code ctx.jobs}, dsh jobs-local analog):
 * owner-scoped (per session) with stable {@code shell-N} ids, a per-session
 * concurrency cap, and process handles so {@code job_kill} can actually stop
 * the work. Jobs are in-process only — they do not survive a restart.
 *
 * <p>Output is captured into a bounded tail (the last {@link #OUTPUT_LIMIT}
 * characters), mirroring how foreground tool results stay readable. The two
 * streams are merged before spawn so a chatty stderr can never deadlock the
 * reader. When a job finishes, the {@link #onFinished} hook fires once — the
 * plugin wires it to the agent-loop inbox so the notice becomes a follow-up
 * turn (busy → next turn, idle → wake).
 */
public final class JobsService extends io.jcordis.core.service.Service {

    public static final String NAME = "jobs";
    public static final int OUTPUT_LIMIT = 8_000;
    static final Logger LOG = LoggerFactory.getLogger(JobsService.class);

    /** Lifecycle of one job. */
    public enum State { RUNNING, COMPLETED, FAILED, KILLED }

    /** One background job (the read model). */
    public static final class Job {
        public final String id;
        public final String kind;
        public final String script;
        public final long startedAtMs;
        public volatile State state = State.RUNNING;
        public volatile long finishedAtMs = 0;
        public volatile int exitCode = -1;
        public volatile String output = "";

        Job(String id, String kind, String script) {
            this.id = id;
            this.kind = kind;
            this.script = script;
            this.startedAtMs = System.currentTimeMillis();
        }
    }

    private final Map<String, Map<String, Job>> bySession = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService runners =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final io.majo.harness.shell.ShellLauncher launcher;
    private final int maxPerSession;
    private final io.majo.harness.sandbox.SandboxService sandbox;
    /** Fires once per finished job with (sessionId, job) — wired to the inbox. */
    private volatile BiConsumer<String, Job> onFinished = (sessionId, job) -> { };

    public JobsService(io.jcordis.core.context.Context ctx,
            io.majo.harness.shell.ShellLauncher launcher, int maxPerSession,
            io.majo.harness.sandbox.SandboxService sandbox) {
        super(ctx, NAME);
        this.launcher = launcher;
        this.maxPerSession = maxPerSession;
        this.sandbox = sandbox;
    }

    /** Spawns {@code script} in the background for the session; fails loud at the cap. */
    public Job start(String sessionId, String script) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("jobs: script must not be blank");
        }
        Map<String, Job> jobs = bySession.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>());
        long running = jobs.values().stream().filter(job -> job.state == State.RUNNING).count();
        if (running >= maxPerSession) {
            throw new IllegalStateException("jobs: session \"" + sessionId + "\" hit the concurrent cap ("
                    + maxPerSession + "); collect or kill a running job first");
        }
        int next = counters.computeIfAbsent(sessionId, ignored -> new AtomicInteger()).incrementAndGet();
        Job job = new Job("shell-" + next, "shell", script);
        jobs.put(job.id, job);

        List<String> argv = new ArrayList<>(launcher.argv(script));
        if (sandbox != null) {
            argv = sandbox.confine(argv);
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.redirectErrorStream(true); // one merged stream: no pipe deadlock
            Process process = builder.start();
            processes.put(sessionId + "/" + job.id, process);
            runners.execute(() -> await(sessionId, job, process));
            return job;
        } catch (IOException e) {
            jobs.remove(job.id);
            throw new IllegalStateException("jobs: cannot spawn \"" + script + "\": " + e.getMessage(), e);
        }
    }

    /** Waits for the process on a virtual thread, recording output and state. */
    private void await(String sessionId, Job job, Process process) {
        String output;
        int exitCode;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (IOException e) {
            output = "[output unreadable] " + e.getMessage();
            exitCode = -1;
        }
        job.exitCode = exitCode;
        job.finishedAtMs = System.currentTimeMillis();
        job.output = tail(output);
        if (job.state != State.KILLED) {
            job.state = exitCode == 0 ? State.COMPLETED : State.FAILED;
        }
        fireFinished(sessionId, job);
    }

    private void fireFinished(String sessionId, Job job) {
        processes.remove(sessionId + "/" + job.id);
        try {
            onFinished.accept(sessionId, job);
        } catch (RuntimeException e) {
            LOG.error("jobs: completion notice for {} failed", job.id, e);
        }
    }

    /** The last {@link #OUTPUT_LIMIT} characters of captured output. */
    private static String tail(String output) {
        return output.length() <= OUTPUT_LIMIT ? output
                : output.substring(output.length() - OUTPUT_LIMIT);
    }

    /** All jobs for the session, oldest first. */
    public List<Job> list(String sessionId) {
        Map<String, Job> jobs = bySession.get(sessionId);
        if (jobs == null) {
            return List.of();
        }
        return jobs.values().stream()
                .sorted(Comparator.comparingLong(job -> job.startedAtMs))
                .toList();
    }

    /** One job, or {@code null} when unknown. */
    public Job get(String sessionId, String jobId) {
        Map<String, Job> jobs = bySession.get(sessionId);
        return jobs == null ? null : jobs.get(jobId);
    }

    /** Destroys the job's process; the runner finalizes it as KILLED. */
    public boolean kill(String sessionId, String jobId) {
        Job job = get(sessionId, jobId);
        if (job == null || job.state != State.RUNNING) {
            return false;
        }
        job.state = State.KILLED;
        Process process = processes.get(sessionId + "/" + jobId);
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        return true;
    }

    /** Sets the completion hook (plugin wiring); returns this for chaining. */
    public JobsService onFinished(BiConsumer<String, Job> hook) {
        this.onFinished = hook == null ? (sessionId, job) -> { } : hook;
        return this;
    }

    /** Shuts the runner pool; running jobs are abandoned (in-process scope). */
    public void close() {
        runners.shutdownNow();
    }
}
