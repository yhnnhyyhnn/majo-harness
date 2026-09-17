package io.majo.harness.web.handler;

import com.sun.net.httpserver.HttpExchange;
import io.majo.harness.web.Http;
import io.majo.harness.web.Metrics;
import io.majo.harness.web.WebApiModels;
import io.majo.harness.web.WebContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** External plugin jars: boot-time/runtime mount, hot reload, unload, index, static frontends. */
public final class PluginHandlers {

    private static final Logger LOG = LoggerFactory.getLogger(PluginHandlers.class);

    private final WebContext ctx;
    /** Booted plugin jars mounted with {@code --plugin name=jar}; serves their static-web/ frontends. */
    private final Map<String, io.jcordis.core.registry.Plugin> webPlugins = new TreeMap<>();
    private final Map<String, Path> pluginJars = new TreeMap<>();

    public PluginHandlers(WebContext ctx) {
        this.ctx = ctx;
    }

    /** Boot-time mount ({@code --plugin name=jar}) before the profile launches. */
    public void mountAtBoot(String name, Path jar, io.jcordis.core.registry.Plugin plugin) {
        webPlugins.put(name, plugin);
        pluginJars.put(name, jar);
    }

    public int mountedCount() {
        return webPlugins.size();
    }

    /**
     * Runtime first-time mount of a plugin jar: {@code POST /api/plugins}
     * with {@code {"name": …, "jar": <path>}} while the server runs. The
     * plugin becomes hot-reloadable (same path) and unloadable through the
     * existing reload/DELETE endpoints.
     */
    public WebApiModels.Ok mount(HttpExchange exchange) throws IOException {
        Map<?, ?> request = Http.JSON.readValue(exchange.getRequestBody(), Map.class);
        String name = request == null ? null : Http.stringValue(request.get("name"));
        String jarValue = request == null ? null : Http.stringValue(request.get("jar"));
        if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) {
            throw new IllegalArgumentException("name must match [a-zA-Z0-9][a-zA-Z0-9._-]*");
        }
        if (jarValue == null || jarValue.isBlank()) {
            throw new IllegalArgumentException("jar path is required");
        }
        if (pluginJars.containsKey(name)) {
            throw new IllegalArgumentException("plugin \"" + name + "\" is already mounted");
        }
        Path jar = Path.of(jarValue);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("plugin jar missing: " + jar);
        }
        io.jcordis.core.registry.Plugin plugin = ctx.boot.loadPluginJar(jar, name);
        webPlugins.put(name, plugin);
        pluginJars.put(name, jar);
        LOG.info("mounted plugin \"{}\" from {}", name, jar);
        return new WebApiModels.Ok(true);
    }

    /** Hot-replaces a mounted plugin jar (classloader swapped, old one closed). */
    public WebApiModels.Ok reload(String name) {
        Path jar = pluginJars.get(name);
        if (jar == null) {
            throw new IllegalArgumentException("unknown plugin \"" + name + "\"");
        }
        if (!Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("plugin jar missing: " + jar);
        }
        io.jcordis.core.registry.Plugin fresh = ctx.boot.loader().replaceJar(jar, name);
        webPlugins.put(name, fresh);
        Metrics.reloaded();
        LOG.info("hot-reloaded plugin \"{}\" from {}", name, jar);
        return new WebApiModels.Ok(true);
    }

    /** Unloads a mounted plugin: fibers torn down, classloader closed, maps cleared. */
    public WebApiModels.Ok unload(String name) {
        io.jcordis.core.registry.Plugin current = webPlugins.get(name);
        if (current == null) {
            throw new IllegalArgumentException("unknown plugin \"" + name + "\"");
        }
        ctx.boot.loader().unload(name);
        webPlugins.remove(name);
        pluginJars.remove(name);
        LOG.info("unloaded plugin \"{}\"", name);
        return new WebApiModels.Ok(true);
    }

    /** Plugins that ship a static frontend ({@code static-web/<name>/}). */
    public WebApiModels.PluginsIndex index() {
        List<WebApiModels.PluginInfo> list = new ArrayList<>();
        for (Map.Entry<String, io.jcordis.core.registry.Plugin> entry : webPlugins.entrySet()) {
            String name = entry.getKey();
            ClassLoader loader = entry.getValue().getClass().getClassLoader();
            if (loader.getResource("static-web/" + name + "/index.html") == null) {
                continue;
            }
            String id = name;
            String title = name;
            String version = null;
            List<String> slots = null;
            try (InputStream manifest = loader.getResourceAsStream("static-web/" + name + "/plugin.json")) {
                if (manifest != null) {
                    var meta = Http.JSON.readTree(manifest.readAllBytes());
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
            Path jar = pluginJars.get(name);
            Long mtime = null;
            if (jar != null && Files.isRegularFile(jar)) {
                try {
                    mtime = Files.getLastModifiedTime(jar).toMillis();
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
    public void asset(HttpExchange exchange, String path) throws IOException {
        int slash = path.indexOf('/', "/plugins/".length());
        if (slash < 0) {
            Http.json(exchange, 404, Map.of("error", "not found: " + path));
            return;
        }
        String name = path.substring("/plugins/".length(), slash);
        String rest = path.substring(slash + 1);
        io.jcordis.core.registry.Plugin plugin = webPlugins.get(name);
        if (plugin == null || rest.contains("..")) {
            Http.json(exchange, 404, Map.of("error", "not found: " + path));
            return;
        }
        String resource = "static-web/" + name + "/" + rest;
        try (InputStream stream = plugin.getClass().getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                Http.json(exchange, 404, Map.of("error", "not found: " + path));
                return;
            }
            byte[] payload = stream.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", Http.contentType(rest));
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
        }
    }
}
