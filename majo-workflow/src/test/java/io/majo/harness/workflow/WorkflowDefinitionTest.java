package io.majo.harness.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Definition parsing, validation, and the pure template language: no
 * expressions, missing keys fail loudly, unknown definitions skipped loudly
 * at load time.
 */
class WorkflowDefinitionTest {

    private static final String VALID = """
            name: review-doc
            description: two step review
            steps:
              - id: fetch
                kind: delegate
                prompt: "read {{args.path}}"
                model: other-model
                allowedTools: [read_file]
              - id: judge
                kind: turn
                prompt: "judge: {{steps.fetch.output}}"
            onFailure: continue
            allowModelTrigger: true
            """;

    @Test
    void parsesValidDefinitions() {
        WorkflowDefinition definition = WorkflowDefinition.parseYaml("review-doc.yml", VALID);
        assertThat(definition.name()).isEqualTo("review-doc");
        assertThat(definition.description()).isEqualTo("two step review");
        assertThat(definition.onFailure()).isEqualTo("continue");
        assertThat(definition.allowModelTrigger()).isTrue();
        assertThat(definition.steps()).hasSize(2);
        assertThat(definition.steps().get(0).kind()).isEqualTo("delegate");
        assertThat(definition.steps().get(0).model()).isEqualTo("other-model");
        assertThat(definition.steps().get(0).allowedTools()).containsExactly("read_file");
        assertThat(definition.steps().get(1).kind()).isEqualTo("turn");
        assertThat(definition.steps().get(1).model()).isNull();
    }

    @Test
    void invalidDefinitionsFailLoudly() {
        assertThatThrownBy(() -> WorkflowDefinition.parseYaml("x.yml", "steps: []"))
                .hasMessageContaining("non-empty");
        assertThatThrownBy(() -> WorkflowDefinition.parseYaml("x.yml", VALID
                .replace("kind: delegate", "kind: magic")))
                .hasMessageContaining("turn|delegate");
        assertThatThrownBy(() -> WorkflowDefinition.parseYaml("x.yml",
                VALID.replace("id: judge", "id: fetch")))
                .hasMessageContaining("duplicate step id");
        assertThatThrownBy(() -> WorkflowDefinition.parseYaml("x.yml", VALID
                .replace("prompt: \"read {{args.path}}\"", "prompt: \"\"")))
                .hasMessageContaining("prompt");
        assertThatThrownBy(() -> WorkflowDefinition.parseYaml("x.yml", VALID
                .replace("onFailure: continue", "onFailure: whatever")))
                .hasMessageContaining("abort|continue");
    }

    @Test
    void interpolationResolvesArgsAndStepOutputsAndFailsOnMissing() {
        Map<String, String> args = Map.of("path", "docs/x.md");
        Map<String, String> outputs = Map.of("fetch", "claim one");
        assertThat(WorkflowDefinition.interpolate(
                "read {{args.path}} then judge {{steps.fetch.output}}", args, outputs))
                .isEqualTo("read docs/x.md then judge claim one");
        assertThatThrownBy(() -> WorkflowDefinition.interpolate(
                "{{steps.missing}}", args, outputs))
                .hasMessageContaining("unresolved template key");
        assertThatThrownBy(() -> WorkflowDefinition.interpolate(
                "{{args.missing}}", args, outputs))
                .hasMessageContaining("unresolved template key");
        // multi-line payloads substitute safely (no regex-group surprises)
        assertThat(WorkflowDefinition.interpolate(
                "out: {{steps.fetch.output}}", args, Map.of("fetch", "line1\n$1 line2")))
                .isEqualTo("out: line1\n$1 line2");
    }

    @Test
    void loadDirSkipsInvalidFilesLoudly(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("good.yml"), VALID.replace("review-doc", "good-one"));
        Files.writeString(dir.resolve("bad.yml"), "steps: []");
        Files.writeString(dir.resolve("notes.txt"), "not a workflow");
        Map<String, WorkflowDefinition> loaded =
                WorkflowDefinition.loadDir(dir, error -> { });
        assertThat(loaded).containsOnlyKeys("good-one");
    }
}
