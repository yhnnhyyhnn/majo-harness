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

        // a missing directory degrades gracefully: no skills, no throw
        FileSkillProvider missing = new FileSkillProvider(root.resolve("missing"));
        assertThat(missing.skills()).isEmpty();
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
    void nameCollisionsResolveByRankInsteadOfFailing(@TempDir Path one, @TempDir Path two) throws Exception {
        writeSkill(one, "shared", "bundled body");
        writeSkill(one, "unique", "only here");
        writeSkill(two, "shared", "project body wins");
        Context ctx = Context.create();
        ctx.plugin(new SkillPlugin(), null).await().join();
        SkillRegistry skills = ctx.get(SkillRegistry.NAME);
        skills.register(new FileSkillProvider(one), "bundled", SkillRegistry.BUNDLED_RANK);
        skills.register(new FileSkillProvider(two), "project", SkillRegistry.PROJECT_RANK);
        // the lower rank wins the duplicated name outright
        assertThat(skills.load("shared").instructions()).isEqualTo("project body wins");
        assertThat(skills.load("shared").source()).isEqualTo("project");
        // the loser's other skills still work
        assertThat(skills.load("unique").instructions()).isEqualTo("only here");
        assertThat(skills.load("unique").source()).isEqualTo("bundled");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void sameRankCollisionKeepsTheFirstRegistration(@TempDir Path one, @TempDir Path two) throws Exception {
        writeSkill(one, "shared", "first");
        writeSkill(two, "shared", "second");
        Context ctx = Context.create();
        ctx.plugin(new SkillPlugin(), null).await().join();
        SkillRegistry skills = ctx.get(SkillRegistry.NAME);
        skills.register(new FileSkillProvider(one), "custom-a", SkillRegistry.CUSTOM_RANK);
        skills.register(new FileSkillProvider(two), "custom-b", SkillRegistry.CUSTOM_RANK);
        assertThat(skills.load("shared").instructions()).isEqualTo("first");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void providerSelfContradictionStillFailsLoud() {
        Context ctx = Context.create();
        ctx.plugin(new SkillPlugin(), null).await().join();
        SkillRegistry skills = ctx.get(SkillRegistry.NAME);
        SkillProvider contradictory = () -> java.util.List.of(
                new Skill("dupe", null, "one"), new Skill("dupe", null, "two"));
        assertThatThrownBy(() -> skills.register(contradictory))
                .isInstanceOf(SkillException.class)
                .hasMessageContaining("dupe");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void scopeRootsComposeIntoOneSourceChain(@TempDir Path project, @TempDir Path custom) throws Exception {
        writeSkill(project, "deploy", "project deploy steps");
        writeSkill(project, "lint", "project lint");
        writeSkill(custom, "deploy", "custom deploy steps");
        Context ctx = Context.create();
        ctx.plugin(new io.majo.harness.tools.ToolsPlugin(), null).await().join();
        ctx.plugin(new SkillPlugin(), null).await().join();
        ctx.plugin(new FileSkillPlugin(), java.util.Map.of(
                "path", project.toString(), "custom", custom.toString())).await().join();
        SkillRegistry skills = ctx.get(SkillRegistry.NAME);
        assertThat(skills.skills()).extracting(Skill::name).containsExactly("deploy", "lint");
        assertThat(skills.load("deploy").source()).isEqualTo("project");
        assertThat(skills.load("lint").source()).isEqualTo("project");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void singleSkillToolLoadsAndTheCatalogRidesTheSystemSection(@TempDir Path root) throws Exception {
        writeSkill(root, "alpha", "alpha instructions");
        Context ctx = Context.create();
        ctx.plugin(new io.majo.harness.tools.ToolsPlugin(), null).await().join();
        ctx.plugin(new SkillPlugin(), null).await().join();
        ctx.plugin(new FileSkillPlugin(), java.util.Map.of("path", root.toString())).await().join();
        ctx.plugin(new SkillToolsPlugin(), null).await().join();
        io.majo.harness.tools.ToolRegistry tools = ctx.get(io.majo.harness.tools.ToolRegistry.NAME);
        assertThat(tools.specs()).extracting(spec -> spec.name())
                .containsExactly("skill");

        io.majo.harness.tools.ToolResult loaded = tools.execute(
                io.majo.harness.tools.ToolCall.of("skill", "{\"skill\":\"alpha\"}"));
        assertThat(loaded.ok()).isTrue();
        assertThat(loaded.content()).isEqualTo("alpha instructions");
        assertThat(loaded.data()).containsEntry("source", "project");

        io.majo.harness.tools.ToolResult missing = tools.execute(
                io.majo.harness.tools.ToolCall.of("skill", "{\"skill\":\"ghost\"}"));
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
