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
    private final HarnessBoot boot;
    private final HttpServer server;
    private final ReentrantLock turnLock = new ReentrantLock();
    private final PendingInteractions pending = new PendingInteractions();
    /** Booted plugin jars mounted with {@code --plugin name=jar}; serves their static-web/ frontends. */
    private final java.util.Map<String, io.jcordis.core.registry.Plugin> webPlugins = new java.util.TreeMap<>();

    public WebMain(int port, String profile) throws IOException {
        this(port, profile, java.util.List.of());
    }

    public WebMain(int port, String profile, java.util.List<String> pluginArgs) throws IOException {
        this.port = port;
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
            System.out.println("majo-web: mounted plugin \"" + name + "\" from " + jar);
        }
        boot.launch(boot.readProfileText(profileText, hint));
        restoreModelPreference();
        InteractionService interactions = boot.ctx().get(InteractionService.NAME);
        if (interactions != null) {
            interactions.registerFront("web-ui", pending);
        }

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
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
        String path = exchange.getRequestURI().getPath();
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
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/subagents".equals(path)) {
                json(exchange, 200, subagentsIndex());
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/plugins".equals(path)) {
                json(exchange, 200, pluginsIndex());
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/info".equals(path)) {
                json(exchange, 200, info());
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/settings/model".equals(path)) {
                json(exchange, 200, modelState());
            } else if ("PUT".equals(exchange.getRequestMethod()) && "/api/settings/model".equals(path)) {
                json(exchange, 200, setModel(exchange));
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/sessions".equals(path)) {
                json(exchange, 200, sessionsIndex());
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
        } catch (Throwable failure) {
            failure.printStackTrace();
            json(exchange, 500, Map.of("error", String.valueOf(failure.getMessage())));
        }
    }

    // ----- API -----

    private WebApiModels.SessionsIndex sessionsIndex() {
        SessionService sessions = boot.service(SessionService.NAME);
        List<WebApiModels.SessionInfo> list = new ArrayList<>();
        for (String sessionId : sessions.sessionIds()) {
            list.add(new WebApiModels.SessionInfo(
                    sessionId, titleFor(sessionId), sessions.events(sessionId).size()));
        }
        return new WebApiModels.SessionsIndex(list);
    }

    private static final String TITLE_PREFIX = "session.title.";
    private static final String SESSION_MODEL_PREFIX = "session.model.";
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
        turnLock.lock();
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
            }
            return new WebApiModels.Ok(true);
        } finally {
            turnLock.unlock();
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

    private WebApiModels.SubagentsIndex subagentsIndex() {
        SubagentService subagent = boot.ctx().get(SubagentService.NAME);
        if (subagent == null) {
            return new WebApiModels.SubagentsIndex(List.of());
        }
        return new WebApiModels.SubagentsIndex(subagent.recentRuns().stream()
                .map(run -> new WebApiModels.SubagentRun(
                        run.task(), run.status(), run.detail(), run.atMillis()))
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
        exchange.sendResponseHeaders(200, 0);
        var out = exchange.getResponseBody();
        try {
            if (task == null || task.isBlank() || sessionId == null || sessionId.isBlank()) {
                frame(out, "fail", new WebApiModels.StreamFail("sessionId and task query parameters are required"));
                return;
            }
            SessionService sessions = boot.service(SessionService.NAME);
            AgentLoopService loop = boot.service(AgentLoopService.NAME);
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
                    frame(out, "log", eventsJson(List.of(event)).get(0));
                }
                return null;
            });
            try {
                turnLock.lock();
                try {
                    pending.notifier = new PendingInteractions.Notifier() {
                        @Override
                        public void approval(ApprovalRequest request) {
                            frame(out, "approval", new WebApiModels.ApprovalFrame(
                                    request.id(), request.summary(), request.details()));
                        }

                        @Override
                        public void question(Question question) {
                            frame(out, "question", new WebApiModels.QuestionFrame(question.id(), question.text()));
                        }
                    };
                    String answer = loop.runTurn(sessionId, task, delta ->
                            frame(out, "chunk", new WebApiModels.StreamChunk(delta)),
                            sessionModelFor(sessionId));
                    frame(out, "done", new WebApiModels.StreamDone(sessionId, answer));
                } finally {
                    pending.notifier = null;
                    turnLock.unlock();
                }
            } finally {
                listener.dispose();
            }
        } catch (Throwable failure) {
            frame(out, "fail", new WebApiModels.StreamFail(String.valueOf(failure.getMessage())));
        } finally {
            out.close();
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
        turnLock.lock();
        try {
            String sessionId = request.get("sessionId") == null
                    ? sessions.createSession()
                    : String.valueOf(request.get("sessionId"));
            String answer = loop.runTurn(sessionId, task, null, sessionModelFor(sessionId));
            return new WebApiModels.TurnResult(sessionId, answer, eventsJson(sessions.events(sessionId)));
        } finally {
            turnLock.unlock();
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
                                ? (Map<String, Object>) (Map<?, ?>) data : null);
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

    // ----- static & plumbing -----

    /** Plugins that ship a static frontend ({@code static-web/<name>/}). */
    private WebApiModels.PluginsIndex pluginsIndex() {
        List<WebApiModels.PluginInfo> list = new ArrayList<>();
        for (java.util.Map.Entry<String, io.jcordis.core.registry.Plugin> entry : webPlugins.entrySet()) {
            String name = entry.getKey();
            ClassLoader loader = entry.getValue().getClass().getClassLoader();
            if (loader.getResource("static-web/" + name + "/index.html") == null) {
                continue;
            }
            String title = name;
            String module = null;
            try (InputStream manifest = loader.getResourceAsStream("static-web/" + name + "/plugin.json")) {
                if (manifest != null) {
                    var meta = JSON.readTree(manifest.readAllBytes());
                    if (meta.hasNonNull("title")) {
                        title = meta.get("title").asText();
                    }
                }
            } catch (IOException ignored) {
                // a broken manifest falls back to the plugin name
            }
            if (loader.getResource("static-web/" + name + "/plugin.mjs") != null) {
                module = "/plugins/" + name + "/plugin.mjs";
            }
            list.add(new WebApiModels.PluginInfo(name,
                    "/plugins/" + name + "/index.html", title, module));
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
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js")) {
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
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
    }

    public static void main(String[] args) throws IOException {
        int port = 8787;
        String profile = "web";
        java.util.List<String> plugins = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--profile" -> profile = args[++i];
                case "--plugin" -> plugins.add(args[++i]);
                default -> {
                    System.err.println("usage: majo-web [--port <n>] [--profile web|<file.yml>] [--plugin name=jar]");
                    System.exit(2);
                }
            }
        }
        WebMain app = new WebMain(port, profile, plugins);
        System.out.println("majo web: http://localhost:" + app.port());
        System.out.println("press Ctrl+C to stop");
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
    }

    /**
     * Web-facing {@link InteractionHandler}: approval and ask-user requests are
     * surfaced to the connected SSE client and park until a decision arrives
     * (120s), then fail safe (deny / empty answer). Registered at the front so
     * it always decides before static fallback handlers.
     */
    static final class PendingInteractions implements InteractionHandler {
        private static final long TIMEOUT_SECONDS = 120;

        interface Notifier {
            void approval(ApprovalRequest request);

            void question(Question question);
        }

        private final java.util.Map<String, java.util.concurrent.CompletableFuture<ApprovalDecision>> approvals =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<String, java.util.concurrent.CompletableFuture<String>> questions =
                new java.util.concurrent.ConcurrentHashMap<>();
        volatile Notifier notifier;

        @Override
        public String name() {
            return "web-ui";
        }

        @Override
        public ApprovalDecision approve(ApprovalRequest request) {
            java.util.concurrent.CompletableFuture<ApprovalDecision> future =
                    new java.util.concurrent.CompletableFuture<>();
            approvals.put(request.id(), future);
            Notifier active = notifier;
            if (active != null) {
                active.approval(request);
            }
            try {
                ApprovalDecision decision = future.get(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
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
            Notifier active = notifier;
            if (active != null) {
                active.question(question);
            }
            try {
                String answer = future.get(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
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
