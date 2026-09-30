package io.majo.harness.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A parsed hooks configuration (the Claude Code {@code hooks.json} shape;
 * Codex's flat map parses identically): an event name → matcher groups →
 * command hooks tree. Only command hooks load — other types are skipped with
 * a warning, mirroring the reference bridges. Invalid regex matchers fail
 * parsing loudly (a typo'd pattern must not silently match nothing).
 *
 * <pre>
 * { "hooks": { "PreToolUse": [
 *     { "matcher": "Bash", "hooks": [ { "type": "command",
 *         "command": "./check.sh", "timeout": 30 } ] }
 * ] } }
 * </pre>
 */
public final class HooksConfig {

    /** One configured command hook ({@code timeout} in seconds, wire unit). */
    public record CommandHook(String command, long timeoutSeconds) {
    }

    /** One matcher group plus the hooks that run when the matcher selects. */
    public record MatcherGroup(String matcher, List<CommandHook> hooks) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, List<MatcherGroup>> byEvent;

    private HooksConfig(Map<String, List<MatcherGroup>> byEvent) {
        this.byEvent = byEvent;
    }

    /** The configured groups for an event name (empty when none). */
    public List<MatcherGroup> groups(String event) {
        return byEvent.getOrDefault(event, List.of());
    }

    /** Every event name that carries at least one group. */
    public List<String> events() {
        return List.copyOf(byEvent.keySet());
    }

    public boolean isEmpty() {
        return byEvent.isEmpty();
    }

    /** Parses a hooks.json tree; unknown shapes fail loudly with a path. */
    public static HooksConfig parse(JsonNode root) {
        JsonNode hooks = root.has("hooks") ? root.get("hooks") : root;
        if (!hooks.isObject()) {
            throw new IllegalArgumentException("hooks: config must be an object of event → groups");
        }
        Map<String, List<MatcherGroup>> byEvent = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> event : new Iterable<Map.Entry<String, JsonNode>>() {
            @Override
            public java.util.Iterator<Map.Entry<String, JsonNode>> iterator() {
                return hooks.fields();
            }
        }) {
            if (!event.getValue().isArray()) {
                throw new IllegalArgumentException(
                        "hooks: \"" + event.getKey() + "\" must be an array of matcher groups");
            }
            List<MatcherGroup> groups = new ArrayList<>();
            for (JsonNode groupNode : event.getValue()) {
                String matcher = groupNode.hasNonNull("matcher")
                        ? groupNode.get("matcher").asText() : null;
                String problem = HookMatcher.diagnostic(matcher);
                if (problem != null) {
                    throw new IllegalArgumentException(
                            "hooks: " + event.getKey() + ": " + problem);
                }
                if (!groupNode.has("hooks") || !groupNode.get("hooks").isArray()) {
                    throw new IllegalArgumentException(
                            "hooks: " + event.getKey() + ": group requires a \"hooks\" array");
                }
                List<CommandHook> commandHooks = new ArrayList<>();
                for (JsonNode hookNode : groupNode.get("hooks")) {
                    String type = hookNode.path("type").asText("command");
                    if (!"command".equals(type)) {
                        // prompt/agent/http hooks are parsed-and-skipped, like the bridges
                        continue;
                    }
                    String command = hookNode.path("command").asText("");
                    if (command.isBlank()) {
                        throw new IllegalArgumentException(
                                "hooks: " + event.getKey() + ": command hook requires \"command\"");
                    }
                    long timeout = hookNode.path("timeout").asLong(0);
                    commandHooks.add(new CommandHook(command, timeout));
                }
                if (!commandHooks.isEmpty()) {
                    groups.add(new MatcherGroup(matcher, List.copyOf(commandHooks)));
                }
            }
            if (!groups.isEmpty()) {
                byEvent.put(event.getKey(), List.copyOf(groups));
            }
        }
        return new HooksConfig(Map.copyOf(byEvent));
    }

    /** Parses from a JSON string (a hooks.json file's content). */
    public static HooksConfig parse(String json) {
        try {
            return parse(MAPPER.readTree(json));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("hooks: cannot parse config: " + e.getMessage(), e);
        }
    }
}
