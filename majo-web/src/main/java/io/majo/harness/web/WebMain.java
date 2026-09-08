package io.majo.harness.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jcordis.core.context.Context;
import io.jcordis.core.logger.ConsoleExporter;
import io.jcordis.core.util.Disposable;
import io.majo.harness.agent.loop.AgentLoopService;
import io.majo.harness.boot.HarnessBoot;
import io.majo.harness.headless.CalculatorToolPlugin;
import io.majo.harness.interaction.ApprovalDecision;
import io.majo.harness.interaction.ApprovalRequest;
import io.majo.harness.interaction.InteractionHandler;
import io.majo.harness.interaction.InteractionService;
import io.majo.harness.interaction.Question;
import io.majo.harness.llm.LLMService;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import io.majo.harness.settings.SettingsService;
import io.majo.harness.skill.Skill;
import io.majo.harness.skill.SkillRegistry;
import io.majo.harness.subagent.SubagentService;
import io.majo.harness.title.SessionTitleService;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The majo web app: serves a static chat UI and a JSON turn API over one
 * booted harness instance (the {@code web} profile). Layout mirrors the dsh
 * web client's core shape — a session sidebar, a conversation of user/tool/
 * assistant bubbles, and a composer — while the server stays dependency-free
 * (JDK HttpServer + Jackson; the UI is vanilla JS, no build step).
 *
 * <p>Usage: {@code java -jar majo-web/target/majo-web-0.1.0-SNAPSHOT.jar [--port 8787]}.
 * Turns are serialized per instance (local single-user).
 */
public final class WebMain {

    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        // optional wire fields (OptionalWire) stay absent instead of null
        JSON.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    }
    private static final String BUILTIN_PROFILE = "web.yml";

    private final int port;
    private final String bindHost;
    private final HarnessBoot boot;
    private final HttpServer server;
    private final ReentrantLock turnLock = new ReentrantLock();
    /** Per-session turn locks: independent sessions may run turns in parallel. */
    private final java.util.Map<String, Object> sessionLocks = new java.util.concurrent.ConcurrentHashMap<>();

    private Object lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, ignored -> new Object());
    }
    private final PendingInteractions pending = new PendingInteractions(approvalTimeoutSeconds());
    /** Optional shared secret: when set, /api/* requires Bearer or ?token=. */
    volatile String authToken;
    private final long startedNanos = System.nanoTime();
    private final java.util.concurrent.atomic.AtomicLong requestCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong errorCount = new java.util.concurrent.atomic.AtomicLong();

    /** Per-request fine metrics for /api/metrics (static: one server per JVM). */
    static final class Metrics {
        private static final java.util.concurrent.atomic.AtomicLong CLIENT_ABORTS =
                new java.util.concurrent.atomic.AtomicLong();
        private static final java.util.concurrent.atomic.AtomicLong TURNS =
                new java.util.concurrent.atomic.AtomicLong();
        private static final java.util.concurrent.atomic.AtomicLong APPROVALS =
                new java.util.concurrent.atomic.AtomicLong();
        private static final java.util.concurrent.atomic.AtomicLong QUESTIONS =
                new java.util.concurrent.atomic.AtomicLong();
        private static final java.util.concurrent.atomic.AtomicLong RELOADS =
                new java.util.concurrent.atomic.AtomicLong();
        private static final long[] BUCKET_LIMITS_MS = {5, 20, 100, 500, 2_000};
        private static final String[] BUCKET_NAMES = {"under5ms", "under20ms", "under100ms",
                "under500ms", "under2000ms", "over2000ms"};
        private static final java.util.concurrent.atomic.AtomicLong[] LATENCY =
                new java.util.concurrent.atomic.AtomicLong[BUCKET_NAMES.length];
        private static final java.util.concurrent.atomic.AtomicLong[] STATUS =
                new java.util.concurrent.atomic.AtomicLong[6];
        private static final ThreadLocal<Long> STARTED = new ThreadLocal<>();

        static {
            for (int i = 0; i < LATENCY.length; i++) {
                LATENCY[i] = new java.util.concurrent.atomic.AtomicLong();
            }
            for (int i = 0; i < STATUS.length; i++) {
                STATUS[i] = new java.util.concurrent.atomic.AtomicLong();
            }
        }

        static void begin() {
            STARTED.set(System.nanoTime());
        }

        /** Records one finished request; clears the per-request timer. */
        static void record(int status) {
            Long started = STARTED.get();
            if (started == null) {
                return;
            }
            STARTED.remove();
            long ms = (System.nanoTime() - started) / 1_000_000;
            int bucket = BUCKET_LIMITS_MS.length;
            for (int i = 0; i < BUCKET_LIMITS_MS.length; i++) {
                if (ms < BUCKET_LIMITS_MS[i]) {
                    bucket = i;
                    break;
                }
            }
            LATENCY[bucket].incrementAndGet();
            if (status >= 100 && status < 600) {
                STATUS[status / 100].incrementAndGet();
            }
        }

        static void abort() {
            CLIENT_ABORTS.incrementAndGet();
            STARTED.remove();
        }

        static void turn() {
            TURNS.incrementAndGet();
        }

        static void approvalDecided() {
            APPROVALS.incrementAndGet();
        }

        static void questionAnswered() {
            QUESTIONS.incrementAndGet();
        }

        static void reloaded() {
            RELOADS.incrementAndGet();
        }

        static Map<String, Object> snapshot(long startedNanos, long requests, long errors) {
            Map<String, Long> latency = new java.util.LinkedHashMap<>();
            for (int i = 0; i < LATENCY.length; i++) {
                latency.put(BUCKET_NAMES[i], LATENCY[i].get());
            }
            Map<String, Long> status = new java.util.LinkedHashMap<>();
            String[] labels = {"1xx", "2xx", "3xx", "4xx", "5xx"};
            for (int i = 1; i <= 5; i++) {
                status.put(labels[i - 1], STATUS[i].get());
            }
            status.put("aborted", CLIENT_ABORTS.get());
            Map<String, Object> all = new java.util.LinkedHashMap<>();
            all.put("uptimeMs", (System.nanoTime() - startedNanos) / 1_000_000);
            all.put("requests", requests);
            all.put("errors", errors);
            all.put("turns", TURNS.get());
            all.put("approvalsDecided", APPROVALS.get());
            all.put("questionsAnswered", QUESTIONS.get());
            all.put("pluginsReloaded", RELOADS.get());
            all.put("status", status);
            all.put("latencyMs", latency);
            return all;
        }
    }
    /** Booted plugin jars mounted with {@code --plugin name=jar}; serves their static-web/ frontends. */
    private final java.util.Map<String, io.jcordis.core.registry.Plugin> webPlugins = new java.util.TreeMap<>();
    private final java.util.Map<String, java.nio.file.Path> pluginJars = new java.util.TreeMap<>();

    public WebMain(int port, String profile) throws IOException {
        this(port, profile, java.util.List.of());
    }

    public WebMain(int port, String profile, java.util.List<String> pluginArgs) throws IOException {
        this(port, profile, pluginArgs, defaultHost(), approvalTimeoutSeconds());
    }

    public WebMain(int port, String profile, java.util.List<String> pluginArgs, String bindHost)
            throws IOException {
        this(port, profile, pluginArgs, bindHost, approvalTimeoutSeconds());
    }

    /** Default bind host: loopback only unless {@code --host} or {@code majo.host} says otherwise. */
    static String defaultHost() {
        String host = System.getProperty("majo.host", "127.0.0.1");
        return host == null || host.isBlank() ? "127.0.0.1" : host;
    }

    private static long approvalTimeoutSeconds() {
        long seconds = Long.getLong("majo.approvalTimeoutSeconds", 30L);
        return seconds <= 0 ? 1 : seconds;
    }

    private WebMain(int port, String profile, java.util.List<String> pluginArgs, String bindHost,
            long approvalTimeoutSeconds) throws IOException {
        this.port = port;
        this.bindHost = bindHost;
        Context root = Context.create();
        new ConsoleExporter(root);
        boot = new HarnessBoot(root)
                .register(CalculatorToolPlugin.NAME, new CalculatorToolPlugin());
        String profileText;
        String hint = profile;
        if ("web".equals(profile) || "web-mock".equals(profile)) {
            hint = profile + ".yml";
            try (InputStream stream = WebMain.class.getClassLoader().getResourceAsStream(hint)) {
                if (stream == null) {
                    throw new IOException("built-in profile missing: " + BUILTIN_PROFILE);
                }
                profileText = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } else {
            profileText = java.nio.file.Files.readString(java.nio.file.Path.of(profile));
        }
        // mount external plugin jars before the profile boots so profile rows
        // can reference them by name (capabilities + static frontends)
        for (String pluginArg : pluginArgs) {
            int equals = pluginArg.indexOf('=');
            if (equals <= 0 || equals == pluginArg.length() - 1) {
                throw new IllegalArgumentException("--plugin expects name=path, got \"" + pluginArg + "\"");
            }
            String name = pluginArg.substring(0, equals);
            java.nio.file.Path jar = java.nio.file.Path.of(pluginArg.substring(equals + 1));
            io.jcordis.core.registry.Plugin plugin = boot.loadPluginJar(jar, name);
            webPlugins.put(name, plugin);
            pluginJars.put(name, jar);
            System.out.println("majo-web: mounted plugin \"" + name + "\" from " + jar);
        }
        boot.launch(boot.readProfileText(profileText, hint));
        restoreModelPreference();
        InteractionService interactions = boot.ctx().get(InteractionService.NAME);
        if (interactions != null) {
            interactions.registerFront("web-ui", pending);
        }
        registerBuiltinCommands();

        try {
            server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        } catch (BindException e) {
            System.err.println("majo-web: port " + port + " is already in use (another instance running?);");
            System.err.println("  pick a free port, e.g. java -jar majo-web-0.1.0-SNAPSHOT.jar --port 9000");
            boot.dispose();
            throw e;
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::route);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void close() {
        server.stop(0);
        boot.dispose();
    }

    private void route(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        Metrics.begin();
        String path = exchange.getRequestURI().getPath();
        String auth = authToken;
        if (auth != null && path.startsWith("/api/") && !authorized(exchange, auth)) {
            json(exchange, 401, Map.of("error", "unauthorized — pass the --token value"));
            return;
        }
        try {
            if ("POST".equals(exchange.getRequestMethod()) && "/api/approvals".equals(path)) {
                throw new IllegalArgumentException("missing approval id");
            } else if ("POST".equals(exchange.getRequestMethod()) && path.startsWith("/api/approvals/")) {
                json(exchange, 200, decideApproval(exchange, path.substring("/api/approvals/".length())));
            } else if ("POST".equals(exchange.getRequestMethod()) && "/api/questions".equals(path)) {
                throw new IllegalArgumentException("missing question id");
            } else if ("POST".equals(exchange.getRequestMethod()) && path.startsWith("/api/questions/")) {
                json(exchange, 200, answerQuestion(exchange, path.substring("/api/questions/".length())));
            } else if (("PUT".equals(exchange.getRequestMethod()) || "DELETE".equals(exchange.getRequestMethod()))
                    && path.startsWith("/api/messages/")
                    && path.endsWith("/feedback")) {
                String rest = path.substring("/api/messages/".length(),
                        path.length() - "/feedback".length());
                int slash = rest.lastIndexOf('/');
                if (slash <= 0) {
                    throw new IllegalArgumentException("feedback path needs sessionId and seq");
                }
                String sessionId = rest.substring(0, slash);
                long seq;
                try {
                    seq = Long.parseLong(rest.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("seq must be a number");
                }
                json(exchange, 200, "DELETE".equals(exchange.getRequestMethod())
                        ? clearFeedback(sessionId, seq)
                        : rateMessage(exchange, sessionId, seq));
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/skills".equals(path)) {
                json(exchange, 200, skillsIndex());
            } else if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/api/skills/")) {
                String name = path.substring("/api/skills/".length());
                json(exchange, 200, skillDetail(name));
            } else if ("POST".equals(exchange.getRequestMethod()) && "/api/subagents/delegate".equals(path)) {
                json(exchange, 200, delegateViaApi(exchange));
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/subagents/islands".equals(path)) {
                SubagentService islandSubagent = boot.ctx().get(SubagentService.NAME);
                java.util.Set<String> names = islandSubagent == null ? java.util.Set.of()
                        : islandSubagent.islandNames();
                json(exchange, 200, java.util.Map.of("islands", names));
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/subagents".equals(path)) {
                json(exchange, 200, subagentsIndex());
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/search".equals(path)) {
                String queryText = query(exchange).getOrDefault("q", "").trim();
                json(exchange, 200, searchIndex(queryText));
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/openapi.json".equals(path)) {
                openApiSpec(exchange);
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/health".equals(path)) {
                json(exchange, 200, healthInfo());
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/metrics".equals(path)) {
                json(exchange, 200, Metrics.snapshot(startedNanos, requestCount.get(), errorCount.get()));
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/commands".equals(path)) {
                json(exchange, 200, commandsIndex());
            } else if ("POST".equals(exchange.getRequestMethod())
                    && path.startsWith("/api/commands/")) {
                String name = path.substring("/api/commands/".length());
                json(exchange, 200, runCommand(exchange, name));
            } else if ("POST".equals(exchange.getRequestMethod())
                    && path.startsWith("/api/plugins/")
                    && path.endsWith("/reload")) {
                String name = path.substring("/api/plugins/".length(),
                        path.length() - "/reload".length());
                json(exchange, 200, reloadPlugin(name));
            } else if ("POST".equals(exchange.getRequestMethod()) && "/api/plugins".equals(path)) {
                json(exchange, 200, mountPlugin(exchange));
            } else if ("DELETE".equals(exchange.getRequestMethod())
                    && path.startsWith("/api/plugins/")) {
                String name = path.substring("/api/plugins/".length());
                json(exchange, 200, unloadPlugin(name));
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/plugins".equals(path)) {
                json(exchange, 200, pluginsIndex());
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/info".equals(path)) {
                json(exchange, 200, info());
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/settings/model".equals(path)) {
                json(exchange, 200, modelState());
            } else if ("PUT".equals(exchange.getRequestMethod()) && "/api/settings/model".equals(path)) {
                json(exchange, 200, setModel(exchange));
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/sessions".equals(path)) {
                json(exchange, 200, sessionsIndex(query(exchange)));
            } else if ("POST".equals(exchange.getRequestMethod()) && "/api/sessions/import".equals(path)) {
                json(exchange, 200, importSession(exchange));
            } else if ("POST".equals(exchange.getRequestMethod()) && "/api/sessions".equals(path)) {
                json(exchange, 200, createSession());
            } else if ("PUT".equals(exchange.getRequestMethod())
                    && path.startsWith("/api/sessions/")
                    && path.endsWith("/title")) {
                String sessionId = path.substring("/api/sessions/".length(),
                        path.length() - "/title".length());
                json(exchange, 200, renameSession(exchange, sessionId));
            } else if ("PUT".equals(exchange.getRequestMethod())
                    && path.startsWith("/api/sessions/")
                    && path.endsWith("/archive")) {
                String sessionId = path.substring("/api/sessions/".length(),
                        path.length() - "/archive".length());
                json(exchange, 200, archiveSession(sessionId, true));
            } else if ("DELETE".equals(exchange.getRequestMethod())
                    && path.startsWith("/api/sessions/")
                    && path.endsWith("/archive")) {
                String sessionId = path.substring("/api/sessions/".length(),
                        path.length() - "/archive".length());
                json(exchange, 200, archiveSession(sessionId, false));
            } else if ("PUT".equals(exchange.getRequestMethod())
                    && path.startsWith("/api/sessions/")
                    && path.endsWith("/model")) {
                String sessionId = path.substring("/api/sessions/".length(),
                        path.length() - "/model".length());
                json(exchange, 200, setSessionModel(exchange, sessionId));
            } else if ("DELETE".equals(exchange.getRequestMethod())
                    && path.startsWith("/api/sessions/")
                    && path.endsWith("/model")) {
                String sessionId = path.substring("/api/sessions/".length(),
                        path.length() - "/model".length());
                json(exchange, 200, clearSessionModel(sessionId));
            } else if ("DELETE".equals(exchange.getRequestMethod())
                    && path.startsWith("/api/sessions/")) {
                String sessionId = path.substring("/api/sessions/".length());
                json(exchange, 200, deleteSession(sessionId));
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/turn/stream".equals(path)) {
                streamTurn(exchange);
            } else if ("GET".equals(exchange.getRequestMethod())
                    && path.startsWith("/api/sessions/")
                    && path.endsWith("/export")) {
                String sessionId = path.substring("/api/sessions/".length(),
                        path.length() - "/export".length());
                exportSession(exchange, sessionId);
            } else if ("GET".equals(exchange.getRequestMethod())
                    && path.startsWith("/api/sessions/")
                    && path.endsWith("/feedback")) {
                String sessionId = path.substring("/api/sessions/".length(),
                        path.length() - "/feedback".length());
                json(exchange, 200, feedbackIndex(sessionId));
            } else if ("GET".equals(exchange.getRequestMethod())
                    && path.startsWith("/api/sessions/")
                    && path.endsWith("/events")) {
                String sessionId = path.substring("/api/sessions/".length(),
                        path.length() - "/events".length());
                json(exchange, 200, eventsSince(sessionId, query(exchange)));
            } else if ("GET".equals(exchange.getRequestMethod())
                    && path.startsWith("/api/sessions/")) {
                String sessionId = path.substring("/api/sessions/".length());
                json(exchange, 200, sessionDetail(sessionId));
            } else if ("POST".equals(exchange.getRequestMethod()) && "/api/turn".equals(path)) {
                json(exchange, 200, turn(exchange));
            } else if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/plugins/")) {
                pluginAsset(exchange, path);
            } else if ("GET".equals(exchange.getRequestMethod())) {
                staticAsset(exchange, path);
            } else {
                json(exchange, 404, Map.of("error", "not found: " + path));
            }
        } catch (IllegalArgumentException e) {
            json(exchange, 400, Map.of("error", e.getMessage()));
        } catch (java.io.IOException e) {
            // distinguish a client dropping the connection (not a server error)
            // from real IO failures inside handlers (Jackson parse errors etc.)
            if (isClientAbort(e)) {
                Metrics.abort();
            } else {
                errorCount.incrementAndGet();
                e.printStackTrace();
                try {
                    json(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
                } catch (IOException lost) {
                    Metrics.abort(); // client vanished while we replied
                }
            }
        } catch (Throwable failure) {
            errorCount.incrementAndGet();
            failure.printStackTrace();
            json(exchange, 500, Map.of("error", String.valueOf(failure.getMessage())));
        }
    }

    private static boolean isClientAbort(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof java.net.SocketException) {
                return true;
            }
            String message = String.valueOf(cursor.getMessage()).toLowerCase();
            if (message.contains("broken pipe") || message.contains("connection reset")
                    || message.contains("socket closed") || message.contains("stream closed")
                    || message.contains("aborted")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    // ----- API -----

    private WebApiModels.SessionsIndex sessionsIndex(Map<String, String> query) {
        SessionService sessions = boot.service(SessionService.NAME);
        String view = query.getOrDefault("view", "active"); // active | archived | all
        List<WebApiModels.SessionInfo> list = new ArrayList<>();
        for (String sessionId : sessions.sessionIds()) {
            boolean archived = isArchived(sessionId);
            if ("active".equals(view) && archived) {
                continue;
            }
            if ("archived".equals(view) && !archived) {
                continue;
            }
            list.add(new WebApiModels.SessionInfo(
                    sessionId, titleFor(sessionId), sessions.events(sessionId).size()));
        }
        return new WebApiModels.SessionsIndex(list);
    }

    private boolean isArchived(String sessionId) {
        SettingsService settings = boot.ctx().get(SettingsService.NAME);
        return settings != null && "1".equals(settings.get(ARCHIVED_PREFIX + sessionId));
    }

    private WebApiModels.Ok archiveSession(String sessionId, boolean archived) {
        SessionService sessions = boot.service(SessionService.NAME);
        if (!sessions.sessionIds().contains(sessionId)) {
            throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
        }
        SettingsService settings = boot.ctx().get(SettingsService.NAME);
        if (settings == null) {
            throw new IllegalArgumentException("settings service unavailable — cannot archive");
        }
        if (archived) {
            settings.set(ARCHIVED_PREFIX + sessionId, "1");
        } else {
            settings.unset(ARCHIVED_PREFIX + sessionId);
        }
        return new WebApiModels.Ok(true);
    }

    private static final String TITLE_PREFIX = "session.title.";
    private static final String SESSION_MODEL_PREFIX = "session.model.";
    private static final String ARCHIVED_PREFIX = "session.archived.";
    private static final String FEEDBACK_PREFIX = "feedback.";

    private WebApiModels.SessionDetail sessionDetail(String sessionId) {
        SessionService sessions = boot.service(SessionService.NAME);
        if (!sessions.sessionIds().contains(sessionId)) {
            throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
        }
        return new WebApiModels.SessionDetail(
                sessionId, titleFor(sessionId), sessionModelFor(sessionId),
                eventsJson(sessions.events(sessionId)));
    }

    /** The per-session model override (settings), or {@code null}. */
    private String sessionModelFor(String sessionId) {
        SettingsService settings = boot.ctx().get(SettingsService.NAME);
        if (settings == null) {
            return null;
        }
        String override = settings.get(SESSION_MODEL_PREFIX + sessionId);
        return override == null || override.isBlank() ? null : override;
    }

    /** User rename wins; otherwise the derived heuristic title. */
    private String titleFor(String sessionId) {
        SettingsService settings = boot.ctx().get(SettingsService.NAME);
        String override = settings == null ? null : settings.get(TITLE_PREFIX + sessionId);
        if (override != null && !override.isBlank()) {
            return override;
        }
        SessionTitleService titles = boot.service(SessionTitleService.NAME);
        return titles.title(sessionId);
    }

    private WebApiModels.FeedbackIndex feedbackIndex(String sessionId) {
        SessionService sessions = boot.service(SessionService.NAME);
        if (!sessions.sessionIds().contains(sessionId)) {
            throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
        }
        SettingsService settings = boot.ctx().get(SettingsService.NAME);
        List<WebApiModels.FeedbackEntry> entries = new ArrayList<>();
        if (settings != null) {
            String prefix = FEEDBACK_PREFIX + sessionId + ".";
            for (Map.Entry<String, String> entry : settings.entries(prefix).entrySet()) {
                String seqText = entry.getKey().substring(prefix.length());
                try {
                    entries.add(new WebApiModels.FeedbackEntry(
                            Long.parseLong(seqText), entry.getValue()));
                } catch (NumberFormatException ignored) {
                    // tolerate stray keys; ratings are best-effort facts
                }
            }
        }
        entries.sort(java.util.Comparator.comparingLong(WebApiModels.FeedbackEntry::seq));
        return new WebApiModels.FeedbackIndex(entries);
    }

    private WebApiModels.Ok rateMessage(HttpExchange exchange, String sessionId, long seq)
            throws IOException {
        requireKnownMessage(sessionId, seq);
        Map<?, ?> request = JSON.readValue(exchange.getRequestBody(), Map.class);
        Object value = request.get("value");
        String rating = value == null ? null : String.valueOf(value);
        if (!"up".equals(rating) && !"down".equals(rating)) {
            throw new IllegalArgumentException("value must be up or down");
        }
        SettingsService settings = boot.ctx().get(SettingsService.NAME);
        if (settings == null) {
            throw new IllegalArgumentException("settings service unavailable — cannot store feedback");
        }
        settings.set(FEEDBACK_PREFIX + sessionId + "." + seq, rating);
        return new WebApiModels.Ok(true);
    }

    private WebApiModels.Ok clearFeedback(String sessionId, long seq) {
        requireKnownMessage(sessionId, seq);
        SettingsService settings = boot.ctx().get(SettingsService.NAME);
        if (settings != null) {
            settings.unset(FEEDBACK_PREFIX + sessionId + "." + seq);
        }
        return new WebApiModels.Ok(true);
    }

    private void requireKnownMessage(String sessionId, long seq) {
        SessionService sessions = boot.service(SessionService.NAME);
        if (!sessions.sessionIds().contains(sessionId)) {
            throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
        }
        boolean known = sessions.events(sessionId).stream().anyMatch(event -> event.seq() == seq);
        if (!known) {
            throw new IllegalArgumentException("unknown event seq " + seq + " in session " + sessionId);
        }
    }

    private WebApiModels.Ok setSessionModel(HttpExchange exchange, String sessionId)
            throws IOException {
        SessionService sessions = boot.service(SessionService.NAME);
        if (!sessions.sessionIds().contains(sessionId)) {
            throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
        }
        Map<?, ?> request = JSON.readValue(exchange.getRequestBody(), Map.class);
        Object modelValue = request.get("model");
        if (modelValue == null || String.valueOf(modelValue).isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        String model = String.valueOf(modelValue);
        LLMService llm = boot.service(LLMService.NAME);
        if (!llm.registeredModels().contains(model)) {
            throw new IllegalArgumentException("unknown model \"" + model + "\"");
        }
        SettingsService settings = boot.ctx().get(SettingsService.NAME);
        if (settings == null) {
            throw new IllegalArgumentException("settings service unavailable — cannot persist model");
        }
        settings.set(SESSION_MODEL_PREFIX + sessionId, model);
        return new WebApiModels.Ok(true);
    }

    private WebApiModels.Ok clearSessionModel(String sessionId) {
        SessionService sessions = boot.service(SessionService.NAME);
        if (!sessions.sessionIds().contains(sessionId)) {
            throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
        }
        SettingsService settings = boot.ctx().get(SettingsService.NAME);
        if (settings != null) {
            settings.unset(SESSION_MODEL_PREFIX + sessionId);
        }
        return new WebApiModels.Ok(true);
    }

    private WebApiModels.Ok renameSession(HttpExchange exchange, String sessionId)
            throws IOException {
        SessionService sessions = boot.service(SessionService.NAME);
        if (!sessions.sessionIds().contains(sessionId)) {
            throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
        }
        Map<?, ?> request = JSON.readValue(exchange.getRequestBody(), Map.class);
        Object titleValue = request.get("title");
        if (titleValue == null || String.valueOf(titleValue).isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        String title = String.valueOf(titleValue).trim();
        if (title.length() > 120) {
            throw new IllegalArgumentException("title too long (max 120)");
        }
        SettingsService settings = boot.ctx().get(SettingsService.NAME);
        if (settings == null) {
            throw new IllegalArgumentException("settings service unavailable — cannot persist title");
        }
        settings.set(TITLE_PREFIX + sessionId, title);
        return new WebApiModels.Ok(true);
    }

    private WebApiModels.Ok deleteSession(String sessionId) {
        SessionService sessions = boot.service(SessionService.NAME);
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            try {
                sessions.remove(sessionId);
                io.majo.harness.session.SessionProjections projections =
                        boot.ctx().get(io.majo.harness.session.SessionProjections.NAME);
                if (projections != null) {
                    projections.drop(sessionId);
                }
                SettingsService settings = boot.ctx().get(SettingsService.NAME);
                if (settings != null) {
                    settings.unset(TITLE_PREFIX + sessionId);
                    settings.unset(SESSION_MODEL_PREFIX + sessionId);
                    settings.unset(ARCHIVED_PREFIX + sessionId);
                }
                return new WebApiModels.Ok(true);
            } finally {
                sessionLocks.remove(sessionId);
            }
        }
    }

    private WebApiModels.SkillsIndex skillsIndex() {
        SkillRegistry skills = boot.ctx().get(SkillRegistry.NAME);
        if (skills == null) {
            return new WebApiModels.SkillsIndex(List.of());
        }
        return new WebApiModels.SkillsIndex(skills.skills().stream()
                .map(skill -> new WebApiModels.SkillInfo(skill.name(), skill.description()))
                .toList());
    }

    /** Direct scoped delegation through the REST surface ({@code /delegate}). */
    private WebApiModels.DelegateResult delegateViaApi(HttpExchange exchange) throws IOException {
        SubagentService subagent = boot.ctx().get(SubagentService.NAME);
        if (subagent == null) {
            throw new IllegalArgumentException("subagent service unavailable — mount the subagent row");
        }
        Map<?, ?> request = JSON.readValue(exchange.getRequestBody(), Map.class);
        Object taskValue = request.get("task");
        if (taskValue == null || String.valueOf(taskValue).isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        String task = String.valueOf(taskValue);
        String model = stringValue(request.get("model"));
        String systemPrompt = stringValue(request.get("systemPrompt"));
        Integer maxSteps = null;
        if (request.get("maxSteps") instanceof Number number) {
            maxSteps = number.intValue();
            if (maxSteps < 1) {
                throw new IllegalArgumentException("maxSteps must be >= 1");
            }
        }
        Boolean autoApprove = null;
        // a programmatic call has no human seat: default to auto-approve
        // unless the caller explicitly opts out
        if (request.get("autoApprove") instanceof Boolean bool) {
            autoApprove = bool;
        } else {
            autoApprove = true;
        }
        java.util.List<String> allowedTools = null;
        if (request.get("allowedTools") instanceof List<?> raw) {
            allowedTools = new ArrayList<>();
            for (Object item : raw) {
                allowedTools.add(String.valueOf(item));
            }
        }
        java.util.List<String> islands = null;
        if (request.get("islands") instanceof List<?> rawIslands) {
            islands = new ArrayList<>();
            for (Object item : rawIslands) {
                islands.add(String.valueOf(item));
            }
        }
        java.util.Map<String, String> settings = null;
        if (request.get("settings") instanceof Map<?, ?> rawSettings) {
            settings = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> item : rawSettings.entrySet()) {
                settings.put(String.valueOf(item.getKey()), String.valueOf(item.getValue()));
            }
        }
        boolean scopeOptions = maxSteps != null || autoApprove != null || allowedTools != null
                || islands != null || settings != null;
        SubagentService.DelegationOutcome outcome;
        if (scopeOptions) {
            SubagentService.AgentSpec spec = new SubagentService.AgentSpec(
                    model, systemPrompt, maxSteps, autoApprove, allowedTools, settings);
            outcome = islands != null
                    ? subagent.delegateSpecIslands(task, spec, islands)
                    : subagent.delegateSpec(task, spec);
        } else {
            outcome = subagent.delegateConfigured(task, model, systemPrompt);
        }
        return new WebApiModels.DelegateResult(outcome.childSessionId(), outcome.answer());
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private WebApiModels.SubagentsIndex subagentsIndex() {
        SubagentService subagent = boot.ctx().get(SubagentService.NAME);
        if (subagent == null) {
            return new WebApiModels.SubagentsIndex(List.of());
        }
        return new WebApiModels.SubagentsIndex(subagent.recentRuns().stream()
                .map(run -> new WebApiModels.SubagentRun(
                        run.task(), run.status(), run.detail(), run.atMillis(),
                        run.model(), run.maxSteps(), run.autoApprove(), run.allowedTools()))
                .toList());
    }

    private WebApiModels.SkillDetail skillDetail(String name) {
        SkillRegistry skills = boot.ctx().get(SkillRegistry.NAME);
        if (skills == null) {
            throw new IllegalArgumentException("skills service unavailable — mount the skills row");
        }
        Skill skill = skills.skills().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown skill \"" + name + "\""));
        return new WebApiModels.SkillDetail(skill.name(), skill.description(), skill.instructions());
    }

    private WebApiModels.Info info() {
        LLMService llm = boot.ctx().get(LLMService.NAME);
        List<String> models = llm == null ? List.of() : llm.registeredModels();
        io.majo.harness.tools.ToolRegistry tools = boot.ctx().get(io.majo.harness.tools.ToolRegistry.NAME);
        List<String> toolNames = tools == null ? List.of()
                : tools.specs().stream().map(spec -> spec.name()).sorted().toList();
        SkillRegistry skills = boot.ctx().get(SkillRegistry.NAME);
        int skillCount = skills == null ? 0 : skills.skills().size();
        return new WebApiModels.Info("0.1.0", models, toolNames, skillCount);
    }

    /** Imports exported JSONL into a brand-new session (returns its id). */
    private WebApiModels.CreateSession importSession(HttpExchange exchange) throws IOException {
        SessionService sessions = boot.service(SessionService.NAME);
        List<SessionEvent> events = new ArrayList<>();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String[] lines = body.split("\r?\n");
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                events.add(JSON.readValue(line, SessionEvent.class));
            } catch (IOException | RuntimeException e) {
                throw new IllegalArgumentException("import: invalid event on line " + (index + 1)
                        + ": " + e.getMessage());
            }
        }
        if (events.isEmpty()) {
            throw new IllegalArgumentException("import: no events in body");
        }
        String sessionId = sessions.createSession();
        try {
            sessions.importEvents(sessionId, events);
        } catch (RuntimeException e) {
            sessions.remove(sessionId); // roll back the half-imported session
            throw e;
        }
        return new WebApiModels.CreateSession(sessionId);
    }

    private WebApiModels.CreateSession createSession() {
        SessionService sessions = boot.service(SessionService.NAME);
        return new WebApiModels.CreateSession(sessions.createSession());
    }

    /** Restores a persisted model choice ({@code settings.web.model}) if valid. */
    private void restoreModelPreference() {
        SettingsService settings = boot.ctx().get(SettingsService.NAME);
        if (settings == null) {
            return;
        }
        String saved = settings.get("web.model");
        if (saved != null) {
            LLMService llm = boot.service(LLMService.NAME);
            if (llm.registeredModels().contains(saved)) {
                llm.defaultModel(saved);
            }
        }
    }

    private WebApiModels.Ok decideApproval(HttpExchange exchange, String id) throws IOException {
        Map<?, ?> request = JSON.readValue(exchange.getRequestBody(), Map.class);
        Object decision = request.get("decision");
        boolean granted = "allow".equalsIgnoreCase(String.valueOf(decision));
        if (!granted && !"reject".equalsIgnoreCase(String.valueOf(decision))) {
            throw new IllegalArgumentException("decision must be allow or reject");
        }
        if (!pending.decideApproval(id, granted)) {
            throw new IllegalArgumentException("unknown or expired approval " + id);
        }
        Metrics.approvalDecided();
        return new WebApiModels.Ok(true);
    }

    private WebApiModels.Ok answerQuestion(HttpExchange exchange, String id) throws IOException {
        Map<?, ?> request = JSON.readValue(exchange.getRequestBody(), Map.class);
        Object answer = request.get("answer");
        if (answer == null) {
            throw new IllegalArgumentException("answer must not be null");
        }
        if (!pending.answerQuestion(id, String.valueOf(answer))) {
            throw new IllegalArgumentException("unknown or expired question " + id);
        }
        Metrics.questionAnswered();
        return new WebApiModels.Ok(true);
    }

    private WebApiModels.ModelState modelState() {
        LLMService llm = boot.service(LLMService.NAME);
        List<String> models = llm.registeredModels();
        String current = llm.currentDefault() != null && models.contains(llm.currentDefault())
                ? llm.currentDefault()
                : models.isEmpty() ? null : models.get(0);
        return new WebApiModels.ModelState(current, models);
    }

    private WebApiModels.ModelState setModel(HttpExchange exchange) throws IOException {
        Map<?, ?> request = JSON.readValue(exchange.getRequestBody(), Map.class);
        Object modelValue = request.get("model");
        if (modelValue == null || String.valueOf(modelValue).isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        String model = String.valueOf(modelValue);
        LLMService llm = boot.service(LLMService.NAME);
        if (!llm.registeredModels().contains(model)) {
            throw new IllegalArgumentException("unknown model \"" + model + "\"");
        }
        llm.defaultModel(model);
        SettingsService settings = boot.ctx().get(SettingsService.NAME);
        if (settings != null) {
            settings.set("web.model", model);
        }
        return modelState();
    }

    /**
     * Server-Sent Events turn: durable appends (except the final assistant
     * text, which streams as chunks) relay as {@code log} frames, text tokens
     * as {@code chunk}, completion as {@code done}, failures as {@code fail}.
     */
    private void streamTurn(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        String sessionId = query.get("sessionId");
        String task = query.get("task");
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        String turnId = java.util.UUID.randomUUID().toString();
        exchange.getResponseHeaders().set("X-Turn-Id", turnId);
        exchange.sendResponseHeaders(200, 0);
        var out = exchange.getResponseBody();
        // All SSE writes share one lock so the heartbeat and event frames never
        // interleave mid-line; the heartbeat keeps idle proxy/NAT connections
        // alive while a turn blocks on an approval decision.
        final Object writeLock = new Object();
        java.util.concurrent.atomic.AtomicBoolean heartbeatOn = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicBoolean clientGone = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            sse(out, writeLock, clientGone, "event: turn\ndata: " + JSON.writeValueAsString(
                    java.util.Map.of("turnId", turnId)) + "\n\n");
            if (task == null || task.isBlank() || sessionId == null || sessionId.isBlank()) {
                sse(out, writeLock, clientGone, "event: fail\ndata: " + JSON.writeValueAsString(
                        new WebApiModels.StreamFail("sessionId and task query parameters are required")) + "\n\n");
                Metrics.record(200);
                return;
            }
            Metrics.turn();
            SessionService sessions = boot.service(SessionService.NAME);
            AgentLoopService loop = boot.service(AgentLoopService.NAME);
            Thread heartbeat = Thread.ofVirtual().start(() -> {
                while (heartbeatOn.get() && !clientGone.get()) {
                    try {
                        Thread.sleep(10_000);
                        if (heartbeatOn.get()) {
                            try {
                                sse(out, writeLock, clientGone, ": hb\n\n");
                            } catch (IOException gone) {
                                clientGone.set(true); // stream dropped: stop
                            }
                        }
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            Disposable listener = boot.ctx().on(SessionService.EVENT, (thisArg, args) -> {
                String seen = (String) args[0];
                io.majo.harness.session.SessionEvent event = (io.majo.harness.session.SessionEvent) args[1];
                if (!sessionId.equals(seen)) {
                    return null;
                }
                boolean finalText = event.type() == SessionEventType.ASSISTANT_MESSAGE
                        && event.content() != null
                        && !event.fields().containsKey(SessionEvent.FIELD_TOOL_CALLS);
                if (!finalText) {
                    try {
                        sse(out, writeLock, clientGone, "event: log\ndata: " + JSON.writeValueAsString(
                                eventsJson(List.of(event)).get(0)) + "\n\n");
                    } catch (IOException silent) {
                        // listener runs on publisher threads; drop frames quietly
                        clientGone.set(true);
                    }
                }
                return null;
            });
            try {
                Object lock = lockFor(sessionId);
                synchronized (lock) {
                    PendingInteractions.Notifier streamNotifier = new PendingInteractions.Notifier() {
                        @Override
                        public void approval(ApprovalRequest request) {
                            try {
                                sse(out, writeLock, clientGone, "event: approval\ndata: " + JSON.writeValueAsString(
                                        new WebApiModels.ApprovalFrame(
                                                request.id(), request.summary(), request.details(), request.agent())) + "\n\n");
                            } catch (IOException silent) {
                                clientGone.set(true);
                            }
                        }

                        @Override
                        public void question(Question question) {
                            try {
                                sse(out, writeLock, clientGone, "event: question\ndata: " + JSON.writeValueAsString(
                                        new WebApiModels.QuestionFrame(
                                                question.id(), question.text(), question.agent())) + "\n\n");
                            } catch (IOException silent) {
                                clientGone.set(true);
                            }
                        }
                    };
                    pending.notifier.set(streamNotifier);
                    try {
                        String answer = loop.runTurn(sessionId, task, delta -> {
                                    try {
                                        sse(out, writeLock, clientGone, "event: chunk\ndata: " + JSON.writeValueAsString(
                                                new WebApiModels.StreamChunk(delta)) + "\n\n");
                                    } catch (IOException silent) {
                                        clientGone.set(true);
                                    }
                                },
                                sessionModelFor(sessionId));
                        sse(out, writeLock, clientGone, "event: done\ndata: " + JSON.writeValueAsString(
                                new WebApiModels.StreamDone(sessionId, answer)) + "\n\n");
                        Metrics.record(200);
                    } finally {
                        pending.notifier.remove();
                    }
                }
            } finally {
                heartbeatOn.set(false);
                listener.dispose();
            }
        } catch (Throwable failure) {
            try {
                sse(out, writeLock, clientGone, "event: fail\ndata: " + JSON.writeValueAsString(
                        new WebApiModels.StreamFail(String.valueOf(failure.getMessage()))) + "\n\n");
            } catch (IOException gone) {
                clientGone.set(true); // nothing left to write; not a server error
            }
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
                clientGone.set(true);
            }
        }
    }

    /** Writes one complete SSE frame under the stream's write lock. */
    private static void sse(java.io.OutputStream out, Object writeLock,
            java.util.concurrent.atomic.AtomicBoolean clientGone, String frame) throws IOException {
        synchronized (writeLock) {
            if (clientGone.get()) {
                throw new IOException("client aborted");
            }
            out.write(frame.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }

    private static void frame(java.io.OutputStream out, String event, Object value) {
        try {
            out.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
            out.write(("data: " + JSON.writeValueAsString(value) + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            // client disconnected mid-stream; the turn keeps its durable log
        }
    }

    private WebApiModels.TurnResult turn(HttpExchange exchange) throws IOException {
        Map<?, ?> request = JSON.readValue(exchange.getRequestBody(), Map.class);
        Object taskValue = request.get("task");
        if (taskValue == null || String.valueOf(taskValue).isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        String task = String.valueOf(taskValue);
        SessionService sessions = boot.service(SessionService.NAME);
        AgentLoopService loop = boot.service(AgentLoopService.NAME);
        String sessionId = request.get("sessionId") == null
                ? sessions.createSession()
                : String.valueOf(request.get("sessionId"));
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            String answer = loop.runTurn(sessionId, task, null, sessionModelFor(sessionId));
            return new WebApiModels.TurnResult(sessionId, answer, eventsJson(sessions.events(sessionId)));
        }
    }

    private static List<WebApiModels.EventFrame> eventsJson(List<SessionEvent> events) {
        List<WebApiModels.EventFrame> result = new ArrayList<>();
        for (SessionEvent event : events) {
            result.add(frameOf(event));
        }
        return result;
    }

    private static WebApiModels.EventFrame frameOf(SessionEvent event) {
        Map<String, Object> fields = event.fields();
        List<WebApiModels.ToolCallFrame> toolCalls = null;
        if (event.type() == SessionEventType.ASSISTANT_MESSAGE
                && fields.containsKey(SessionEvent.FIELD_TOOL_CALLS)
                && fields.get(SessionEvent.FIELD_TOOL_CALLS) instanceof List<?> calls) {
            toolCalls = new ArrayList<>();
            for (Object item : calls) {
                if (item instanceof Map<?, ?> call) {
                    toolCalls.add(new WebApiModels.ToolCallFrame(
                            stringField(call, SessionEvent.FIELD_TOOL_NAME),
                            stringField(call, SessionEvent.FIELD_ARGUMENTS),
                            stringField(call, SessionEvent.FIELD_TOOL_CALL_ID)));
                }
            }
        }
        List<String> toolNames = null;
        if (event.type() == SessionEventType.REQUEST_HEADER
                && fields.get(SessionEvent.FIELD_TOOL_NAMES) instanceof List<?> raw) {
            toolNames = raw.stream().map(String::valueOf).toList();
        }
        return new WebApiModels.EventFrame(
                event.seq(),
                event.type().name(),
                event.content(),
                toolCalls,
                event.type() == SessionEventType.TOOL_RESULT
                        ? stringField(fields, SessionEvent.FIELD_TOOL_NAME) : null,
                event.type() == SessionEventType.TOOL_RESULT
                        ? fields.get(SessionEvent.FIELD_OK) instanceof Boolean ok ? ok : null : null,
                event.type() == SessionEventType.REQUEST_HEADER
                        ? stringField(fields, SessionEvent.FIELD_MODEL) : null,
                toolNames,
                event.type() == SessionEventType.TOOL_RESULT
                        && fields.get(SessionEvent.FIELD_DATA) instanceof Map<?, ?> data
                                ? (Map<String, Object>) (Map<?, ?>) data : null,
                event.timestamp());
    }

    /** Downloads a session as replayable JSONL (raw durable events). */
    private void exportSession(HttpExchange exchange, String sessionId) throws IOException {
        SessionService sessions = boot.service(SessionService.NAME);
        if (!sessions.sessionIds().contains(sessionId)) {
            json(exchange, 404, Map.of("error", "unknown session \"" + sessionId + "\""));
            return;
        }
        StringBuilder out = new StringBuilder();
        for (SessionEvent event : sessions.events(sessionId)) {
            try {
                out.append(JSON.writeValueAsString(event)).append(System.lineSeparator());
            } catch (IOException e) {
                throw new IllegalStateException("cannot serialize session event", e);
            }
        }
        byte[] payload = out.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"session-" + sessionId + ".jsonl\"");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(200, payload.length);
        if (payload.length > 0) {
            exchange.getResponseBody().write(payload);
        }
        // closing the body emits the final chunk (empty exports included);
        // without it the JDK HttpClient waits on a terminator that never comes
        exchange.getResponseBody().close();
    }

    /** Events after a durable cursor (lightweight catch-up for big sessions). */
    private WebApiModels.EventsDelta eventsSince(String sessionId, Map<String, String> query) {
        SessionService sessions = boot.service(SessionService.NAME);
        if (!sessions.sessionIds().contains(sessionId)) {
            throw new IllegalArgumentException("unknown session \"" + sessionId + "\"");
        }
        long since = 0;
        if (query.get("since") != null && !query.get("since").isBlank()) {
            try {
                since = Long.parseLong(query.get("since"));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("since must be a non-negative number");
            }
            if (since < 0) {
                throw new IllegalArgumentException("since must be a non-negative number");
            }
        }
        List<WebApiModels.EventFrame> newer = new ArrayList<>();
        long last = since;
        for (SessionEvent event : sessions.events(sessionId)) {
            if (event.seq() > since) {
                newer.add(frameOf(event));
            }
            last = Math.max(last, event.seq());
        }
        return new WebApiModels.EventsDelta(since, last, newer);
    }

    private static String stringField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** Serves the curated OpenAPI descriptor ({@code openapi.json} resource). */
    private void openApiSpec(HttpExchange exchange) throws IOException {
        try (InputStream stream = WebMain.class.getClassLoader()
                .getResourceAsStream("openapi.json")) {
            if (stream == null) {
                json(exchange, 404, Map.of("error", "openapi.json missing"));
                return;
            }
            byte[] payload = stream.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
        }
    }

    /** Liveness + counters for operators. */
    private WebApiModels.HealthInfo healthInfo() {
        SessionService sessions = boot.ctx().get(SessionService.NAME);
        io.majo.harness.llm.LLMService llm = boot.ctx().get(io.majo.harness.llm.LLMService.NAME);
        io.majo.harness.tools.ToolRegistry tools = boot.ctx().get(io.majo.harness.tools.ToolRegistry.NAME);
        return new WebApiModels.HealthInfo(true,
                (System.nanoTime() - startedNanos) / 1_000_000,
                "0.1.0",
                sessions == null ? 0 : sessions.sessionIds().size(),
                webPlugins.size(),
                tools == null ? 0 : tools.specs().size(),
                llm == null ? 0 : llm.registeredModels().size(),
                requestCount.get(),
                errorCount.get());
    }

    /** Registered backend commands (ctx.commands; roadmap B1). */
    private WebApiModels.CommandsIndex commandsIndex() {
        io.majo.harness.boot.commands.CommandRegistry commands =
                boot.ctx().get(io.majo.harness.boot.commands.CommandRegistry.NAME);
        if (commands == null) {
            return new WebApiModels.CommandsIndex(List.of());
        }
        return new WebApiModels.CommandsIndex(commands.entries().stream()
                .map(entry -> new WebApiModels.CommandInfo(entry.name(), entry.description()))
                .toList());
    }

    private WebApiModels.CommandResult runCommand(HttpExchange exchange, String name)
            throws IOException {
        io.majo.harness.boot.commands.CommandRegistry commands =
                boot.ctx().get(io.majo.harness.boot.commands.CommandRegistry.NAME);
        if (commands == null) {
            throw new IllegalArgumentException("commands service unavailable — mount the commands row");
        }
        Map<?, ?> request = JSON.readValue(exchange.getRequestBody(), Map.class);
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
        if (request != null) {
            for (Map.Entry<?, ?> entry : request.entrySet()) {
                args.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        String output = commands.run(name, args);
        return new WebApiModels.CommandResult(output == null ? "" : output);
    }

    /** Full-text search across durable session events (title + message text). */
    private WebApiModels.SearchIndex searchIndex(String queryText) {
        if (queryText.isEmpty()) {
            return new WebApiModels.SearchIndex(List.of());
        }
        SessionService sessions = boot.service(SessionService.NAME);
        String needle = queryText.toLowerCase();
        List<WebApiModels.SearchHit> hits = new ArrayList<>();
        for (String sessionId : sessions.sessionIds()) {
            if (hits.size() >= 25) {
                break;
            }
            String title = titleFor(sessionId);
            Boolean archived = isArchived(sessionId);
            if (title.toLowerCase().contains(needle)) {
                hits.add(new WebApiModels.SearchHit(sessionId, title, "title match", null, archived));
                continue;
            }
            String snippet = null;
            Long seq = null;
            for (SessionEvent event : sessions.events(sessionId)) {
                String content = event.content();
                if (content != null && content.toLowerCase().contains(needle)) {
                    seq = event.seq();
                    snippet = content.replaceAll("\\s+", " ").trim();
                    int at = snippet.toLowerCase().indexOf(needle);
                    if (snippet.length() > 160) {
                        int start = Math.max(0, at - 60);
                        snippet = (start > 0 ? "…" : "") + snippet.substring(start)
                                .substring(0, Math.min(160, snippet.length() - start)) + "…";
                    }
                    break;
                }
            }
            if (snippet != null) {
                hits.add(new WebApiModels.SearchHit(sessionId, title, snippet, seq, archived));
            }
        }
        return new WebApiModels.SearchIndex(hits);
    }

    // ----- static & plumbing -----

    /**
     * Runtime first-time mount of a plugin jar: {@code POST /api/plugins}
     * with {@code {"name": …, "jar": <path>}} while the server runs. The
     * plugin becomes hot-reloadable (same path) and unloadable through the
     * existing reload/DELETE endpoints.
     */
    private WebApiModels.Ok mountPlugin(HttpExchange exchange) throws IOException {
        Map<?, ?> request = JSON.readValue(exchange.getRequestBody(), Map.class);
        String name = request == null ? null : stringValue(request.get("name"));
        String jarValue = request == null ? null : stringValue(request.get("jar"));
        if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) {
            throw new IllegalArgumentException("name must match [a-zA-Z0-9][a-zA-Z0-9._-]*");
        }
        if (jarValue == null || jarValue.isBlank()) {
            throw new IllegalArgumentException("jar path is required");
        }
        if (pluginJars.containsKey(name)) {
            throw new IllegalArgumentException("plugin \"" + name + "\" is already mounted");
        }
        java.nio.file.Path jar = java.nio.file.Path.of(jarValue);
        if (!java.nio.file.Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("plugin jar missing: " + jar);
        }
        io.jcordis.core.registry.Plugin plugin = boot.loadPluginJar(jar, name);
        webPlugins.put(name, plugin);
        pluginJars.put(name, jar);
        System.out.println("majo-web: mounted plugin \"" + name + "\" from " + jar);
        return new WebApiModels.Ok(true);
    }

    /** Hot-replaces a mounted plugin jar (classloader swapped, old one closed). */
    private WebApiModels.Ok reloadPlugin(String name) {
        java.nio.file.Path jar = pluginJars.get(name);
        if (jar == null) {
            throw new IllegalArgumentException("unknown plugin \"" + name + "\"");
        }
        if (!java.nio.file.Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("plugin jar missing: " + jar);
        }
        io.jcordis.core.registry.Plugin fresh = boot.loader().replaceJar(jar, name);
        webPlugins.put(name, fresh);
        Metrics.reloaded();
        System.out.println("majo-web: hot-reloaded plugin \"" + name + "\" from " + jar);
        return new WebApiModels.Ok(true);
    }

    /** Unloads a mounted plugin: fibers torn down, classloader closed, maps cleared. */
    private WebApiModels.Ok unloadPlugin(String name) {
        io.jcordis.core.registry.Plugin current = webPlugins.get(name);
        if (current == null) {
            throw new IllegalArgumentException("unknown plugin \"" + name + "\"");
        }
        boot.loader().unload(name);
        webPlugins.remove(name);
        pluginJars.remove(name);
        System.out.println("majo-web: unloaded plugin \"" + name + "\"");
        return new WebApiModels.Ok(true);
    }

    // ----- static & plumbing -----

    /** Registers the host's builtin backend commands (roadmap B1/#3). */
    private void registerBuiltinCommands() {
        io.majo.harness.boot.commands.CommandRegistry commands =
                boot.ctx().get(io.majo.harness.boot.commands.CommandRegistry.NAME);
        if (commands == null) {
            return;
        }
        commands.register("status", "harness counters (sessions/plugins/tools/models)",
                (ctx, args) -> statusText());
        commands.register("delegate", "run a scoped child delegation (task, model?)", (ctx, args) -> {
            Object taskValue = args.get("task");
            if (taskValue == null || String.valueOf(taskValue).isBlank()) {
                throw new IllegalArgumentException("task must not be blank");
            }
            SubagentService subagent = boot.ctx().get(SubagentService.NAME);
            if (subagent == null) {
                throw new IllegalArgumentException("subagent service unavailable");
            }
            String model = args.get("model") == null ? null : String.valueOf(args.get("model"));
            SubagentService.DelegationOutcome outcome =
                    subagent.delegateConfigured(String.valueOf(taskValue), model, null);
            return outcome.childSessionId() + " → " + outcome.answer();
        });
    }

    private String statusText() {
        io.majo.harness.session.SessionService sessions = boot.ctx().get(
                io.majo.harness.session.SessionService.NAME);
        io.majo.harness.llm.LLMService llm = boot.ctx().get(io.majo.harness.llm.LLMService.NAME);
        io.majo.harness.tools.ToolRegistry tools = boot.ctx().get(io.majo.harness.tools.ToolRegistry.NAME);
        return "sessions=" + (sessions == null ? 0 : sessions.sessionIds().size())
                + " plugins=" + webPlugins.size()
                + " tools=" + (tools == null ? 0 : tools.specs().size())
                + " models=" + (llm == null ? 0 : llm.registeredModels().size());
    }

    /** Whether a request carries the expected token (Bearer header or ?token=). */
    private static boolean authorized(HttpExchange exchange, String expected) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.equals("Bearer " + expected)) {
            return true;
        }
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("token=")) {
                    return part.substring(6).equals(expected);
                }
            }
        }
        return false;
    }

    /** Plugins that ship a static frontend ({@code static-web/<name>/}). */
    private WebApiModels.PluginsIndex pluginsIndex() {
        List<WebApiModels.PluginInfo> list = new ArrayList<>();
        for (java.util.Map.Entry<String, io.jcordis.core.registry.Plugin> entry : webPlugins.entrySet()) {
            String name = entry.getKey();
            ClassLoader loader = entry.getValue().getClass().getClassLoader();
            if (loader.getResource("static-web/" + name + "/index.html") == null) {
                continue;
            }
            String id = name;
            String title = name;
            String version = null;
            java.util.List<String> slots = null;
            try (InputStream manifest = loader.getResourceAsStream("static-web/" + name + "/plugin.json")) {
                if (manifest != null) {
                    var meta = JSON.readTree(manifest.readAllBytes());
                    if (meta.hasNonNull("id")) {
                        id = meta.get("id").asText();
                    }
                    if (meta.hasNonNull("title")) {
                        title = meta.get("title").asText();
                    }
                    if (meta.hasNonNull("version")) {
                        version = meta.get("version").asText();
                    }
                    if (meta.hasNonNull("slots") && meta.get("slots").isArray()) {
                        slots = new ArrayList<>();
                        for (var item : meta.get("slots")) {
                            slots.add(item.asText());
                        }
                    }
                }
            } catch (IOException ignored) {
                // a broken manifest falls back to the plugin name
            }
            String module = null;
            if (loader.getResource("static-web/" + name + "/plugin.mjs") != null) {
                module = "/plugins/" + name + "/plugin.mjs";
            }
            java.nio.file.Path jar = pluginJars.get(name);
            Long mtime = null;
            if (jar != null && java.nio.file.Files.isRegularFile(jar)) {
                try {
                    mtime = java.nio.file.Files.getLastModifiedTime(jar).toMillis();
                } catch (IOException ignored) {
                    // missing/modified jar just has no mtime
                }
            }
            list.add(new WebApiModels.PluginInfo(name,
                    "/plugins/" + name + "/index.html", id, title, module, version, slots, mtime));
        }
        return new WebApiModels.PluginsIndex(list);
    }

    /** Serves {@code static-web/<name>/<rest>} from the plugin jar. */
    private void pluginAsset(HttpExchange exchange, String path) throws IOException {
        int slash = path.indexOf('/', "/plugins/".length());
        if (slash < 0) {
            json(exchange, 404, Map.of("error", "not found: " + path));
            return;
        }
        String name = path.substring("/plugins/".length(), slash);
        String rest = path.substring(slash + 1);
        io.jcordis.core.registry.Plugin plugin = webPlugins.get(name);
        if (plugin == null || rest.contains("..")) {
            json(exchange, 404, Map.of("error", "not found: " + path));
            return;
        }
        String resource = "static-web/" + name + "/" + rest;
        try (InputStream stream = plugin.getClass().getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                json(exchange, 404, Map.of("error", "not found: " + path));
                return;
            }
            byte[] payload = stream.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType(rest));
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
        }
    }

    private static void staticAsset(HttpExchange exchange, String path) throws IOException {
        if ("/".equals(path)) {
            path = "/index.html";
        }
        String resource = "static" + path;
        try (InputStream stream = WebMain.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                json(exchange, 404, Map.of("error", "not found: " + path));
                return;
            }
            byte[] payload = stream.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType(path));
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            Metrics.record(200);
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js") || path.endsWith(".mjs")) {
            return "text/javascript; charset=utf-8";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (path.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (path.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (path.endsWith(".json")) {
            return "application/json";
        }
        return "application/octet-stream";
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] payload = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        Metrics.record(status);
    }

    public static void main(String[] args) throws IOException {
        int port = 8787;
        String host = defaultHost();
        String profile = "web";
        String token = null;
        java.util.List<String> plugins = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--profile" -> profile = args[++i];
                case "--plugin" -> plugins.add(args[++i]);
                case "--token" -> token = args[++i];
                default -> {
                    System.err.println("usage: majo-web [--host <addr>] [--port <n>]"
                            + " [--profile web|<file.yml>] [--plugin name=jar] [--token <secret>]");
                    System.exit(2);
                }
            }
        }
        WebMain app = new WebMain(port, profile, plugins, host);
        app.authToken = token;
        if (token != null) {
            System.out.println("majo web: API auth enabled (--token); pass ?token=... or Authorization: Bearer");
        } else if (!isLoopback(host)) {
            System.err.println("majo web: WARNING binding " + host
                    + " without --token exposes every API to the network;"
                    + " pass --token <secret> or bind 127.0.0.1");
        }
        System.out.println("majo web: http://" + host + ":" + app.port());
        System.out.println("press Ctrl+C to stop");
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase();
        if ("localhost".equals(lower)) {
            return true;
        }
        try {
            java.net.InetAddress address = java.net.InetAddress.getByName(host);
            return address.isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    /**
     * Web-facing {@link InteractionHandler}: approval and ask-user requests are
     * surfaced to the connected SSE client and park until a decision arrives
     * (120s), then fail safe (deny / empty answer). Registered at the front so
     * it always decides before static fallback handlers.
     */
    static final class PendingInteractions implements InteractionHandler {
        private final long timeoutSeconds;

        interface Notifier {
            void approval(ApprovalRequest request);

            void question(Question question);
        }

        private final java.util.Map<String, java.util.concurrent.CompletableFuture<ApprovalDecision>> approvals =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<String, java.util.concurrent.CompletableFuture<String>> questions =
                new java.util.concurrent.ConcurrentHashMap<>();

        PendingInteractions(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
        /**
         * Per-stream notifier carried by the handling thread (and inherited by
         * child virtual threads spawned inside a turn), so concurrent turns on
         * different sessions route approvals/questions to their own SSE stream.
         */
        final java.lang.InheritableThreadLocal<Notifier> notifier =
                new java.lang.InheritableThreadLocal<>();

        @Override
        public String name() {
            return "web-ui";
        }

        @Override
        public ApprovalDecision approve(ApprovalRequest request) {
            java.util.concurrent.CompletableFuture<ApprovalDecision> future =
                    new java.util.concurrent.CompletableFuture<>();
            approvals.put(request.id(), future);
            Notifier active = notifier.get();
            if (active != null) {
                active.approval(request);
            }
            try {
                ApprovalDecision decision = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
                return decision == null ? ApprovalDecision.DENY : decision;
            } catch (Exception e) {
                return ApprovalDecision.DENY; // fail safe
            } finally {
                approvals.remove(request.id());
            }
        }

        @Override
        public String answer(Question question) {
            java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
            questions.put(question.id(), future);
            Notifier active = notifier.get();
            if (active != null) {
                active.question(question);
            }
            try {
                String answer = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
                return answer == null ? "" : answer;
            } catch (Exception e) {
                return "";
            } finally {
                questions.remove(question.id());
            }
        }

        /** Completes a pending approval; false when unknown/expired. */
        boolean decideApproval(String id, boolean granted) {
            java.util.concurrent.CompletableFuture<ApprovalDecision> future = approvals.get(id);
            if (future == null) {
                return false;
            }
            future.complete(granted ? ApprovalDecision.APPROVE : ApprovalDecision.DENY);
            return true;
        }

        /** Completes a pending question; false when unknown/expired. */
        boolean answerQuestion(String id, String text) {
            java.util.concurrent.CompletableFuture<String> future = questions.get(id);
            if (future == null) {
                return false;
            }
            future.complete(text);
            return true;
        }
    }
}
