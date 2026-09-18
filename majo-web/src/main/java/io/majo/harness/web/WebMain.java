package io.majo.harness.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jcordis.core.context.Context;
import io.jcordis.core.logger.ConsoleExporter;
import io.majo.harness.boot.HarnessBoot;
import io.majo.harness.headless.CalculatorToolPlugin;
import io.majo.harness.interaction.InteractionService;
import io.majo.harness.web.handler.AssetHandlers;
import io.majo.harness.web.handler.CommandHandlers;
import io.majo.harness.web.handler.InteractionHandlers;
import io.majo.harness.web.handler.ModelHandlers;
import io.majo.harness.web.handler.OpsHandlers;
import io.majo.harness.web.handler.PluginHandlers;
import io.majo.harness.web.handler.SearchHandlers;
import io.majo.harness.web.handler.SessionHandlers;
import io.majo.harness.web.handler.SkillHandlers;
import io.majo.harness.web.handler.SubagentHandlers;
import io.majo.harness.web.handler.TurnHandlers;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The majo web app: serves a static chat UI and a JSON turn API over one
 * booted harness instance (the {@code web} profile). Layout mirrors the dsh
 * web client's core shape — a session sidebar, a conversation of user/tool/
 * assistant bubbles, and a composer — while the server stays dependency-free
 * (JDK HttpServer + Jackson; the UI is vanilla JS, no build step).
 *
 * <p>This class is assembly only: boot + route table + main(). Request
 * handling lives in {@code handler/}, shared plumbing in {@link Http},
 * {@link Router}, {@link Metrics}, {@link PendingInteractions}.
 *
 * <p>Usage: {@code java -jar majo-web.jar [--port 8787]}. Turns are
 * serialized per session (local single-user, parallel across sessions).
 */
public final class WebMain {

    private static final Logger LOG = LoggerFactory.getLogger(WebMain.class);
    private static final String BUILTIN_PROFILE = "web.yml";

    private final String bindHost;
    private final HarnessBoot boot;
    private final HttpServer server;
    private final WebContext ctx;
    private final Router router = new Router();

    public WebMain(int port, String profile) throws IOException {
        this(port, profile, List.of());
    }

    public WebMain(int port, String profile, List<String> pluginArgs) throws IOException {
        this(port, profile, pluginArgs, defaultHost());
    }

    public WebMain(int port, String profile, List<String> pluginArgs, String bindHost)
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

    private WebMain(int port, String profile, List<String> pluginArgs, String bindHost,
            long approvalTimeoutSeconds) throws IOException {
        this.bindHost = bindHost;
        Context root = Context.create();
        new ConsoleExporter(root);
        boot = new HarnessBoot(root)
                .register(CalculatorToolPlugin.NAME, new CalculatorToolPlugin());
        ctx = new WebContext(boot, new PendingInteractions(approvalTimeoutSeconds));

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

        PluginHandlers plugins = new PluginHandlers(ctx);
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
            plugins.mountAtBoot(name, jar, plugin);
            LOG.info("mounted plugin \"{}\" from {}", name, jar);
        }

        boot.launch(boot.readProfileText(profileText, hint));
        ModelHandlers models = new ModelHandlers(ctx);
        models.restorePreference();
        InteractionService interactions = boot.ctx().get(InteractionService.NAME);
        if (interactions != null) {
            interactions.registerFront("web-ui", ctx.pending);
        }
        CommandHandlers commands = new CommandHandlers(ctx, plugins);
        commands.registerBuiltins();

        registerRoutes(plugins, models, commands);
        try {
            server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        } catch (BindException e) {
            LOG.error("port {} is already in use (another instance running?); pick a free port, e.g. --port 9000", port);
            boot.dispose();
            throw e;
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::route);
        server.start();
    }

    /** Registers every route; order matters, same as the historical chain. */
    private void registerRoutes(PluginHandlers plugins, ModelHandlers models, CommandHandlers commands) {
        SessionHandlers sessions = new SessionHandlers(ctx);
        TurnHandlers turns = new TurnHandlers(ctx);
        InteractionHandlers interactions = new InteractionHandlers(ctx);
        SubagentHandlers subagents = new SubagentHandlers(ctx);
        OpsHandlers ops = new OpsHandlers(ctx, plugins);
        SearchHandlers search = new SearchHandlers(ctx);
        SkillHandlers skills = new SkillHandlers(ctx);
        AssetHandlers assets = new AssetHandlers();

        router
                .post("/api/approvals", (exchange, rest) -> {
                    throw new IllegalArgumentException("missing approval id");
                })
                .post("/api/approvals/*", (exchange, id) -> Http.json(exchange, 200, interactions.decideApproval(exchange, id)))
                .post("/api/questions", (exchange, rest) -> {
                    throw new IllegalArgumentException("missing question id");
                })
                .post("/api/questions/*", (exchange, id) -> Http.json(exchange, 200, interactions.answerQuestion(exchange, id)))
                .on("PUT|DELETE", "/api/messages/*/feedback", (exchange, rest) -> {
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
                    Http.json(exchange, 200, "DELETE".equals(exchange.getRequestMethod())
                            ? sessions.clearFeedback(sessionId, seq)
                            : sessions.rateMessage(exchange, sessionId, seq));
                })
                .get("/api/skills", (exchange, rest) -> Http.json(exchange, 200, skills.index()))
                .get("/api/skills/*", (exchange, name) -> Http.json(exchange, 200, skills.detail(name)))
                .post("/api/subagents/delegate", (exchange, rest) -> Http.json(exchange, 200, subagents.delegate(exchange)))
                .get("/api/subagents/islands", (exchange, rest) -> Http.json(exchange, 200, subagents.islands()))
                .get("/api/subagents", (exchange, rest) -> Http.json(exchange, 200, subagents.subagentsIndex()))
                .get("/api/search", (exchange, rest) -> Http.json(exchange, 200,
                        search.search(Http.query(exchange).getOrDefault("q", "").trim())))
                .get("/api/openapi.json", (exchange, rest) -> ops.openApiSpec(exchange))
                .get("/api/health", (exchange, rest) -> Http.json(exchange, 200, ops.health()))
                .get("/api/metrics", (exchange, rest) -> Http.json(exchange, 200, ops.metricsSnapshot()))
                .get("/api/commands", (exchange, rest) -> Http.json(exchange, 200, commands.index()))
                .post("/api/commands/*", (exchange, name) -> Http.json(exchange, 200, commands.run(exchange, name)))
                .post("/api/plugins/*/reload", (exchange, name) -> Http.json(exchange, 200, plugins.reload(name)))
                .post("/api/plugins", (exchange, rest) -> Http.json(exchange, 200, plugins.mount(exchange)))
                .delete("/api/plugins/*", (exchange, name) -> Http.json(exchange, 200, plugins.unload(name)))
                .get("/api/plugins", (exchange, rest) -> Http.json(exchange, 200, plugins.index()))
                .get("/api/info", (exchange, rest) -> Http.json(exchange, 200, ops.info()))
                .get("/api/settings/model", (exchange, rest) -> Http.json(exchange, 200, models.state()))
                .put("/api/settings/model", (exchange, rest) -> Http.json(exchange, 200, models.set(exchange)))
                .get("/api/sessions", (exchange, rest) -> Http.json(exchange, 200, sessions.sessionsIndex(Http.query(exchange))))
                .post("/api/sessions/import", (exchange, rest) -> Http.json(exchange, 200, sessions.importSession(exchange)))
                .post("/api/sessions", (exchange, rest) -> Http.json(exchange, 200, sessions.createSession()))
                .put("/api/sessions/*/title", (exchange, id) -> Http.json(exchange, 200, sessions.renameSession(exchange, id)))
                .put("/api/sessions/*/archive", (exchange, id) -> Http.json(exchange, 200, sessions.archiveSession(id, true)))
                .delete("/api/sessions/*/archive", (exchange, id) -> Http.json(exchange, 200, sessions.archiveSession(id, false)))
                .put("/api/sessions/*/model", (exchange, id) -> Http.json(exchange, 200, sessions.setSessionModel(exchange, id)))
                .delete("/api/sessions/*/model", (exchange, id) -> Http.json(exchange, 200, sessions.clearSessionModel(id)))
                .delete("/api/sessions/*", (exchange, id) -> Http.json(exchange, 200, sessions.deleteSession(id)))
                .get("/api/turn/stream", (exchange, rest) -> turns.streamTurn(exchange))
                .get("/api/sessions/*/export", (exchange, id) -> sessions.exportSession(exchange, id))
                .get("/api/sessions/*/feedback", (exchange, id) -> Http.json(exchange, 200, sessions.feedbackIndex(id)))
                .get("/api/sessions/*/todos", (exchange, id) -> Http.json(exchange, 200, sessions.todos(id)))
                .get("/api/sessions/*/plan", (exchange, id) -> Http.json(exchange, 200, sessions.plan(id)))
                .get("/api/sessions/*/jobs", (exchange, id) -> Http.json(exchange, 200, sessions.jobs(id)))
                .get("/api/sessions/*/schedules", (exchange, id) -> Http.json(exchange, 200, sessions.schedules(id)))
                .get("/api/sessions/*/context", (exchange, id) -> Http.json(exchange, 200, sessions.context(id)))
                .get("/api/sessions/*/events", (exchange, id) -> Http.json(exchange, 200, sessions.eventsSince(id, Http.query(exchange))))
                .get("/api/mentions", (exchange, rest) -> Http.json(exchange, 200, sessions.mentionSuggestions(Http.query(exchange))))
                .post("/api/sessions/*/mentions", (exchange, id) -> Http.json(exchange, 200, sessions.injectMention(exchange, id)))
                .get("/api/sessions/*", (exchange, id) -> Http.json(exchange, 200, sessions.sessionDetail(id)))
                .post("/api/turn", (exchange, rest) -> Http.json(exchange, 200, turns.turn(exchange)))
                .get("/plugins/*", (exchange, rest) -> plugins.asset(exchange, "/plugins/" + rest))
                .get("*", (exchange, rest) -> assets.staticAsset(exchange, exchange.getRequestURI().getPath()));
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void close() {
        server.stop(0);
        boot.dispose();
    }

    private void route(HttpExchange exchange) throws IOException {
        ctx.requestCount.incrementAndGet();
        Metrics.begin();
        String path = exchange.getRequestURI().getPath();
        String auth = ctx.authToken;
        if (auth != null && path.startsWith("/api/") && !Http.authorized(exchange, auth)) {
            Http.json(exchange, 401, Map.of("error", "unauthorized — pass the --token value"));
            return;
        }
        try {
            router.dispatch(exchange);
        } catch (IllegalArgumentException e) {
            Http.json(exchange, 400, Map.of("error", e.getMessage()));
        } catch (IOException e) {
            // distinguish a client dropping the connection (not a server error)
            // from real IO failures inside handlers (Jackson parse errors etc.)
            if (Http.isClientAbort(e)) {
                Metrics.abort();
            } else {
                ctx.errorCount.incrementAndGet();
                LOG.error("request {} failed", path, e);
                try {
                    Http.json(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
                } catch (IOException lost) {
                    Metrics.abort(); // client vanished while we replied
                }
            }
        } catch (Throwable failure) {
            ctx.errorCount.incrementAndGet();
            LOG.error("request {} failed", path, failure);
            Http.json(exchange, 500, Map.of("error", String.valueOf(failure.getMessage())));
        }
    }

    public static void main(String[] args) throws IOException {
        int port = 8787;
        String host = defaultHost();
        String profile = "web";
        String token = null;
        List<String> plugins = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--profile" -> profile = args[++i];
                case "--plugin" -> plugins.add(args[++i]);
                case "--token" -> token = args[++i];
                default -> {
                    LOG.error("usage: majo-web [--host <addr>] [--port <n>]"
                            + " [--profile web|<file.yml>] [--plugin name=jar] [--token <secret>]");
                    System.exit(2);
                }
            }
        }
        WebMain app = new WebMain(port, profile, plugins, host);
        app.ctx.authToken = token;
        if (token != null) {
            LOG.info("API auth enabled (--token); pass ?token=... or Authorization: Bearer");
        } else if (!isLoopback(host)) {
            LOG.warn("binding {} without --token exposes every API to the network;"
                    + " pass --token <secret> or bind 127.0.0.1", host);
        }
        LOG.info("majo web: http://{}:{}", host, app.port());
        LOG.info("press Ctrl+C to stop");
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
}
