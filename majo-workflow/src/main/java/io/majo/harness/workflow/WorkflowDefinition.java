package io.majo.harness.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;

/**
 * One parsed workflow definition (design: docs/workflow-design.md): a named,
 * ordered step list — {@code turn} (a model turn in the run's child session)
 * and {@code delegate} (a scoped child delegation) — with pure
 * {@code {{args.*}}} / {@code {{steps.<id>.output}}} template interpolation
 * and no expression language (missing keys fail the step loudly).
 */
public record WorkflowDefinition(String name, String description, String onFailure,
        boolean allowModelTrigger, List<Step> steps) {

    public static final String ON_FAILURE_ABORT = "abort";
    public static final String ON_FAILURE_CONTINUE = "continue";

    /** One step: kind {@code turn}|{@code delegate} plus optional scope overrides. */
    public record Step(String id, String kind, String prompt, String model,
            String systemPrompt, Integer maxSteps, Boolean autoApprove,
            List<String> allowedTools, boolean parallel) {

        public Step(String id, String kind, String prompt, String model,
                String systemPrompt, Integer maxSteps, Boolean autoApprove,
                List<String> allowedTools) {
            this(id, kind, prompt, model, systemPrompt, maxSteps, autoApprove,
                    allowedTools, false);
        }
    }

    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{([^}]+)}}");

    /** Parses one definition from YAML text; {@code fileName} names unnamed workflows. */
    public static WorkflowDefinition parseYaml(String fileName, String yaml) {
        Object loaded = new Yaml().load(yaml);
        if (!(loaded instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("workflow \"" + fileName
                    + "\": definition must be a YAML mapping");
        }
        String name = raw.get("name") == null
                ? fileName.replaceAll("\\.ya?ml$", "")
                : String.valueOf(raw.get("name"));
        require(name.matches("[a-z0-9][a-z0-9-]*"), "name must match [a-z0-9][a-z0-9-]*");
        String description = raw.get("description") == null
                ? null : String.valueOf(raw.get("description"));
        String onFailure = raw.get("onFailure") == null
                ? ON_FAILURE_ABORT : String.valueOf(raw.get("onFailure"));
        require(onFailure.equals(ON_FAILURE_ABORT) || onFailure.equals(ON_FAILURE_CONTINUE),
                "onFailure must be abort|continue");
        boolean allowModelTrigger = Boolean.TRUE.equals(raw.get("allowModelTrigger"));

        if (!(raw.get("steps") instanceof List<?> rawSteps) || rawSteps.isEmpty()) {
            throw new IllegalArgumentException("workflow \"" + name
                    + "\": steps must be a non-empty list");
        }
        List<Step> steps = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object item : rawSteps) {
            if (!(item instanceof Map<?, ?> rawStep)) {
                throw new IllegalArgumentException("workflow \"" + name
                        + "\": each step must be a mapping");
            }
            String id = text(rawStep, "id");
            require(id != null && id.matches("[a-z0-9][a-z0-9-]*"),
                    "step id must match [a-z0-9][a-z0-9-]*");
            require(ids.add(id), "duplicate step id \"" + id + "\"");
            String kind = text(rawStep, "kind");
            require(kind != null && (kind.equals("turn") || kind.equals("delegate")),
                    "step \"" + id + "\": kind must be turn|delegate");
            String prompt = text(rawStep, "prompt");
            require(prompt != null && !prompt.isBlank(),
                    "step \"" + id + "\": prompt must not be blank");
            Integer maxSteps = null;
            if (rawStep.get("maxSteps") instanceof Number number && number.intValue() > 0) {
                maxSteps = number.intValue();
            }
            List<String> allowedTools = null;
            if (rawStep.get("allowedTools") instanceof List<?> tools) {
                allowedTools = tools.stream().map(String::valueOf).toList();
            }
            steps.add(new Step(id, kind, prompt,
                    text(rawStep, "model"), text(rawStep, "systemPrompt"),
                    maxSteps,
                    rawStep.get("autoApprove") instanceof Boolean b ? b : null,
                    allowedTools,
                    Boolean.TRUE.equals(rawStep.get("parallel"))));
        }
        return new WorkflowDefinition(name, description, onFailure, allowModelTrigger,
                List.copyOf(steps));
    }

    /**
     * Resolves {@code {{args.*}}} and {@code {{steps.<id>.output}}} as pure
     * string substitution; an unresolvable key fails loudly.
     */
    public static String interpolate(String template, Map<String, String> args,
            Map<String, String> outputs) {
        Matcher matcher = TEMPLATE.matcher(template);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value;
            if (key.startsWith("args.")) {
                value = args.get(key.substring(5));
            } else if (key.startsWith("steps.") && key.endsWith(".output")) {
                value = outputs.get(key.substring(6, key.length() - ".output".length()));
            } else {
                value = null;
            }
            if (value == null) {
                throw new IllegalArgumentException("unresolved template key {{"
                        + key + "}}");
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    /** Loads every valid {@code *.yml|*.yaml} under {@code dir}; invalid files are skipped loudly. */
    static Map<String, WorkflowDefinition> loadDir(Path directory,
            java.util.function.Consumer<String> errorSink) throws IOException {
        Map<String, WorkflowDefinition> definitions = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) {
            return Map.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path file : paths.filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".yml") || name.endsWith(".yaml");
            }).sorted().toList()) {
                String fileName = file.getFileName().toString();
                try {
                    WorkflowDefinition definition =
                            parseYaml(fileName, Files.readString(file));
                    if (definitions.containsKey(definition.name())) {
                        errorSink.accept("workflow file \"" + fileName
                                + "\": duplicate name \"" + definition.name() + "\"");
                        continue;
                    }
                    definitions.put(definition.name(), definition);
                } catch (RuntimeException e) {
                    errorSink.accept("workflow file \"" + fileName + "\": " + e.getMessage());
                }
            }
        }
        return definitions;
    }

    private static String text(Map<?, ?> map, String key) {
        return map.get(key) == null ? null : String.valueOf(map.get(key));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("workflow: " + message);
        }
    }
}
