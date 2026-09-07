package io.majo.harness.boot.commands;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.jcordis.core.util.Disposable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Backend command registry ({@code ctx.commands}, roadmap B1): plugins and
 * host code register named commands that run with harness context and a
 * request map; the web app lists them ({@code GET /api/commands}) and invokes
 * them ({@code POST /api/commands/<name>}). Registrations are Disposables so
 * unloading a plugin rolls its commands back.
 */
public final class CommandRegistry extends Service {

    public static final String NAME = "commands";

    private final Map<String, Entry> commands = new ConcurrentHashMap<>();

    public CommandRegistry(Context ctx) {
        super(ctx, NAME);
    }

    public Disposable register(String name, String description,
            BiFunction<Context, Map<String, Object>, String> handler) {
        String key = name.toLowerCase();
        if (key.isBlank()) {
            throw new IllegalArgumentException("commands: name must not be blank");
        }
        Entry created = new Entry(name, description, handler);
        Entry previous = commands.putIfAbsent(key, created);
        if (previous != null) {
            throw new IllegalStateException("command \"" + key + "\" has been registered");
        }
        return () -> commands.remove(key, created);
    }

    /** Registered command names, sorted. */
    public List<Entry> entries() {
        return commands.values().stream()
                .sorted((a, b) -> a.name.compareToIgnoreCase(b.name))
                .toList();
    }

    /** Executes a registered command by name; unknown names fail loudly. */
    public String run(String name, Map<String, Object> args) {
        Entry entry = commands.get(name.toLowerCase());
        if (entry == null) {
            throw new IllegalArgumentException("unknown command \"" + name
                    + "\"; registered: " + commands.keySet());
        }
        return entry.handler.apply(ctx, args == null ? Map.of() : args);
    }

    /** One registered command (public for wire DTOs/tests). */
    public record Entry(String name, String description,
            BiFunction<Context, Map<String, Object>, String> handler) {}
}
