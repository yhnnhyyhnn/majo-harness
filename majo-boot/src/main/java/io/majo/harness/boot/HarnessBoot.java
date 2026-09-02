package io.majo.harness.boot;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import io.jcordis.loader.include.ConfigParser;
import io.majo.harness.agent.loop.AgentLoopPlugin;
import io.majo.harness.fs.FsPlugin;
import io.majo.harness.fs.FsToolPlugin;
import io.majo.harness.llm.LLMServicePlugin;
import io.majo.harness.llm.MockLLMPlugin;
import io.majo.harness.provider.openai.OpenAiProviderPlugin;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionProjectionsPlugin;
import io.majo.harness.shell.ShellPlugin;
import io.majo.harness.shell.ShellToolPlugin;
import io.majo.harness.subprocess.SubprocessPlugin;
import io.majo.harness.subprocess.SubprocessToolPlugin;
import io.majo.harness.tools.ToolsPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Profile-to-loader glue, mirroring the dsh boot layer: registers the shipped
 * harness plugins as loader builtins (so profile files reference them by name)
 * and boots an entry tree from a profile.
 *
 * <p>Applications mount their own plugins next to the shipped set with
 * {@link #register}; every entry of a profile is a named row that the loader
 * activates when its injections are satisfied.
 */
public final class HarnessBoot {

    public static final String PLUGIN_SESSION = SessionPlugin.NAME;
    public static final String PLUGIN_SESSION_PROJECTIONS = SessionProjectionsPlugin.NAME;
    public static final String PLUGIN_TOOLS = ToolsPlugin.NAME;
    public static final String PLUGIN_LLM = LLMServicePlugin.NAME;
    public static final String PLUGIN_LLM_MOCK = MockLLMPlugin.NAME;
    public static final String PLUGIN_LLM_OPENAI = OpenAiProviderPlugin.NAME;
    public static final String PLUGIN_FS = FsPlugin.NAME;
    public static final String PLUGIN_FS_TOOLS = FsToolPlugin.NAME;
    public static final String PLUGIN_SUBPROCESS = SubprocessPlugin.NAME;
    public static final String PLUGIN_SUBPROCESS_TOOLS = SubprocessToolPlugin.NAME;
    public static final String PLUGIN_SHELL = ShellPlugin.NAME;
    public static final String PLUGIN_SHELL_TOOLS = ShellToolPlugin.NAME;
    public static final String PLUGIN_AGENT_LOOP = AgentLoopPlugin.NAME;

    private final Context ctx;
    private final Loader loader;

    public HarnessBoot(Context ctx) {
        this.ctx = ctx;
        this.loader = new Loader(ctx);
        registerDefaults();
    }

    private void registerDefaults() {
        loader.builtin(PLUGIN_SESSION, new SessionPlugin());
        loader.builtin(PLUGIN_SESSION_PROJECTIONS, new SessionProjectionsPlugin());
        loader.builtin(PLUGIN_TOOLS, new ToolsPlugin());
        loader.builtin(PLUGIN_LLM, new LLMServicePlugin());
        loader.builtin(PLUGIN_LLM_MOCK, new MockLLMPlugin());
        loader.builtin(PLUGIN_LLM_OPENAI, new OpenAiProviderPlugin());
        loader.builtin(PLUGIN_FS, new FsPlugin());
        loader.builtin(PLUGIN_FS_TOOLS, new FsToolPlugin());
        loader.builtin(PLUGIN_SUBPROCESS, new SubprocessPlugin());
        loader.builtin(PLUGIN_SUBPROCESS_TOOLS, new SubprocessToolPlugin());
        loader.builtin(PLUGIN_SHELL, new ShellPlugin());
        loader.builtin(PLUGIN_SHELL_TOOLS, new ShellToolPlugin());
        loader.builtin(PLUGIN_AGENT_LOOP, new AgentLoopPlugin());
    }

    /** Registers an application plugin so profiles can reference its name. */
    public HarnessBoot register(String name, Plugin plugin) {
        loader.builtin(name, plugin);
        return this;
    }

    /** Registers an out-of-tree plugin (e.g. loaded from a plugin jar). */
    public HarnessBoot registerModule(String name, Plugin plugin) {
        loader.modules.put(name, plugin);
        return this;
    }

    /** Parses a profile file (YAML/JSON by extension) into entry options. */
    public List<EntryOptions> readProfile(Path file) {
        try {
            String content = Files.readString(file);
            return ConfigParser.forPath(file.getFileName().toString()).read(content);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read profile " + file, e);
        }
    }

    /** Parses profile text ({@code hint} provides the format by extension). */
    public List<EntryOptions> readProfileText(String content, String hint) {
        try {
            return ConfigParser.forPath(hint).read(content);
        } catch (IOException e) {
            throw new IllegalStateException("cannot parse profile", e);
        }
    }

    /**
     * Boots the entry tree, failing loudly when a row names an unregistered
     * plugin, then waits until every entry task settles.
     */
    public HarnessBoot launch(List<EntryOptions> entries) {
        validate(entries);
        loader.read(entries);
        loader.await();
        return this;
    }

    private void validate(List<EntryOptions> entries) {
        for (EntryOptions entry : entries) {
            if (entry.group != null && entry.group) {
                validateChildren(entry.config);
                continue;
            }
            if (entry.name == null
                    || (!loader.builtins.containsKey(entry.name) && !loader.modules.containsKey(entry.name))) {
                throw new IllegalArgumentException(
                        "profile row \"" + entry.id + "\" names unknown plugin \"" + entry.name
                                + "\"; registered: " + loader.builtins.keySet());
            }
            if (entry.config instanceof List<?> list && !list.isEmpty()) {
                throw new IllegalArgumentException(
                        "profile row \"" + entry.id + "\" carries a child config but is not a group");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateChildren(Object config) {
        if (config instanceof List<?> list) {
            validate(list.stream().map(item -> {
                if (item instanceof EntryOptions entry) {
                    return entry;
                }
                throw new IllegalArgumentException("invalid nested group row: " + item);
            }).toList());
        }
    }

    /** Builds a profile row. */
    public static EntryOptions entry(String id, String name) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        return options;
    }

    /** Builds a profile row with config. */
    public static EntryOptions entry(String id, String name, Object config) {
        EntryOptions options = entry(id, name);
        options.config = config;
        return options;
    }

    /** Builds a profile row with config and inject declarations. */
    public static EntryOptions entry(String id, String name, Object config, Map<String, Object> inject) {
        EntryOptions options = entry(id, name, config);
        options.inject = inject;
        return options;
    }

    /** The loader running this boot (for state assertions and live changes). */
    public Loader loader() {
        return loader;
    }

    /** The root context of the booted tree. */
    public Context ctx() {
        return ctx;
    }

    /** Resolves a service by name from the root context. */
    public <T> T service(String name) {
        return ctx.get(name);
    }

    /** Tears the whole tree down: disposes the root fiber and its plugins. */
    public void dispose() {
        ctx.fiber().disposeAsync().join();
    }
}
