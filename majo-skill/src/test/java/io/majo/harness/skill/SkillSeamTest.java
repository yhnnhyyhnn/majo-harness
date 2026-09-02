package io.majo.harness.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillSeamTest {

    private static Path writeSkill(Path root, String name, String content) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(FileSkillProvider.SKILL_FILE), content);
        return dir;
    }

    @Test
    void fileProviderScansSkillDirectoriesAndParsesFrontMatter(@TempDir Path root) throws Exception {
        writeSkill(root, "alpha", """
                ---
                description: runs alpha things
                ---
                # Alpha skill
                step one.
                """);
        writeSkill(root, "beta", "plain skill body");
        Files.createDirectories(root.resolve("empty-dir")); // no SKILL.md: not a skill

        FileSkillProvider provider = new FileSkillProvider(root);
        assertThat(provider.skills()).extracting(Skill::name).containsExactly("alpha", "beta");
        Skill alpha = provider.skills().get(0);
        assertThat(alpha.description()).isEqualTo("runs alpha things");
        assertThat(alpha.instructions()).startsWith("# Alpha skill").contains("step one.");
        assertThat(provider.skills().get(1).instructions()).isEqualTo("plain skill body");

        assertThatThrownBy(() -> new FileSkillProvider(root.resolve("missing")))
                .isInstanceOf(SkillException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void registryAggregatesLoadsAndFailsLoud(@TempDir Path root) throws Exception {
        writeSkill(root, "alpha", "alpha instructions");
        writeSkill(root, "beta", "beta instructions");
        Context ctx = Context.create();
        ctx.plugin(new SkillPlugin(), null).await().join();
        SkillRegistry skills = ctx.get(SkillRegistry.NAME);
        assertThat(skills.hasProviders()).isFalse();
        assertThatThrownBy(() -> skills.load("alpha")).isInstanceOf(SkillException.class);

        io.jcordis.core.util.Disposable registration = skills.register(new FileSkillProvider(root));
        assertThat(skills.hasProviders()).isTrue();
        assertThat(skills.skills()).extracting(Skill::name).containsExactly("alpha", "beta");
        assertThat(skills.load("beta").instructions()).isEqualTo("beta instructions");
        assertThatThrownBy(() -> skills.load("nope"))
                .isInstanceOf(SkillException.class)
                .hasMessageContaining("unknown skill \"nope\"");

        registration.dispose();
        assertThat(skills.hasProviders()).isFalse();
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void nameCollisionsAcrossProvidersFailLoud(@TempDir Path one, @TempDir Path two) throws Exception {
        writeSkill(one, "shared", "first");
        writeSkill(two, "shared", "second");
        writeSkill(two, "unique", "only here");
        Context ctx = Context.create();
        ctx.plugin(new SkillPlugin(), null).await().join();
        SkillRegistry skills = ctx.get(SkillRegistry.NAME);
        skills.register(new FileSkillProvider(one));
        assertThatThrownBy(() -> skills.register(new FileSkillProvider(two)))
                .isInstanceOf(SkillException.class)
                .hasMessageContaining("shared");
        // the colliding provider registered nothing: unique is not visible
        assertThat(skills.skills()).extracting(Skill::name).containsExactly("shared");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void toolsBrowseCatalogAndLoadSkills(@TempDir Path root) throws Exception {
        writeSkill(root, "alpha", "alpha instructions");
        Context ctx = Context.create();
        ctx.plugin(new io.majo.harness.tools.ToolsPlugin(), null).await().join();
        ctx.plugin(new SkillPlugin(), null).await().join();
        ctx.plugin(new FileSkillPlugin(), java.util.Map.of("path", root.toString())).await().join();
        ctx.plugin(new SkillToolsPlugin(), null).await().join();
        io.majo.harness.tools.ToolRegistry tools = ctx.get(io.majo.harness.tools.ToolRegistry.NAME);
        assertThat(tools.specs()).extracting(spec -> spec.name())
                .containsExactly("list_skills", "load_skill");

        io.majo.harness.tools.ToolResult catalog = tools.execute(
                io.majo.harness.tools.ToolCall.of("list_skills", "{}"));
        assertThat(catalog.ok()).isTrue();
        assertThat(catalog.content()).contains("- alpha");

        io.majo.harness.tools.ToolResult loaded = tools.execute(
                io.majo.harness.tools.ToolCall.of("load_skill", "{\"skill\":\"alpha\"}"));
        assertThat(loaded.ok()).isTrue();
        assertThat(loaded.content()).isEqualTo("alpha instructions");

        io.majo.harness.tools.ToolResult missing = tools.execute(
                io.majo.harness.tools.ToolCall.of("load_skill", "{\"skill\":\"ghost\"}"));
        assertThat(missing.ok()).isFalse();
        assertThat(missing.visibleText()).contains("unknown skill");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void missingPathFailsLoud() {
        Context ctx = Context.create();
        ctx.plugin(new SkillPlugin(), null).await().join();
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                ctx.plugin(new FileSkillPlugin(), List.of()).await().join());
        assertThat(thrown).isNotNull();
        assertThat(rootCause(thrown)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
        ctx.fiber().disposeAsync().join();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor;
    }
}
