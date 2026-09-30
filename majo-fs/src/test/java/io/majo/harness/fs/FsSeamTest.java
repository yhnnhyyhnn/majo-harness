package io.majo.harness.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jcordis.core.context.Context;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FsSeamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void localProviderReadsWritesAndGlobs(@TempDir Path dir) {
        Context root = Context.create();
        root.plugin(new FsPlugin(), null).await().join();
        FileSystemService fs = root.get(FileSystemService.NAME);

        fs.writeText(dir.resolve("a.txt").toString(), "hello");
        fs.writeText(dir.resolve("nested").resolve("b.txt").toString(), "world");
        assertThat(fs.readText(dir.resolve("a.txt").toString())).isEqualTo("hello");
        assertThat(fs.glob(dir.toString(), "*.txt")).containsExactly(dir.resolve("a.txt").toString());
        assertThat(fs.glob(dir.resolve("nested").toString(), "*.txt"))
                .containsExactly(dir.resolve("nested").resolve("b.txt").toString());
        // recursive ** crosses directories; plain * does not
        assertThat(fs.glob(dir.toString(), "**/*.txt"))
                .containsExactly(dir.resolve("a.txt").toString(),
                        dir.resolve("nested").resolve("b.txt").toString());

        assertThatThrownBy(() -> fs.readText(dir.resolve("missing.txt").toString()))
                .isInstanceOf(FsException.class)
                .hasMessageContaining("cannot read");
        assertThatThrownBy(() -> fs.readText(dir.toString()))
                .isInstanceOf(FsException.class)
                .hasMessageContaining("directory");
        root.fiber().disposeAsync().join();
    }

    @Test
    void localProviderGrepsContentRecursively(@TempDir Path dir) throws Exception {
        Context root = Context.create();
        root.plugin(new FsPlugin(), null).await().join();
        FileSystemService fs = root.get(FileSystemService.NAME);

        fs.writeText(dir.resolve("One.java").toString(), "class One {\n  int answer = 42;\n}\n");
        fs.writeText(dir.resolve("nested").resolve("Two.md").toString(), "the answer is 42\n");
        // raw bytes with a NUL marker: this file is binary for the grep probe
        Files.write(dir.resolve("skip.bin"), new byte[] {'a', 'n', 's', 'w', 'e', 'r', 0, '4', '2'});

        // text hits across the tree, binary files skipped
        assertThat(fs.grep(dir.toString(), "42", null, 100))
                .containsExactly(
                        dir.resolve("One.java").toAbsolutePath() + ":2:  int answer = 42;",
                        dir.resolve("nested").resolve("Two.md").toAbsolutePath() + ":1:the answer is 42");
        // include filter and single-file mode
        assertThat(fs.grep(dir.toString(), "42", "*.md", 100))
                .containsExactly(dir.resolve("nested").resolve("Two.md").toAbsolutePath() + ":1:the answer is 42");
        assertThat(fs.grep(dir.resolve("One.java").toString(), "answer", null, 100))
                .hasSize(1);
        // head limit truncates
        assertThat(fs.grep(dir.toString(), "42", null, 1)).hasSize(1);
        // invalid regex fails loudly
        assertThatThrownBy(() -> fs.grep(dir.toString(), "([", null, 100))
                .isInstanceOf(FsException.class)
                .hasMessageContaining("invalid grep pattern");
        root.fiber().disposeAsync().join();
    }

    @Test
    void policyListenerRejectsByThrowing(@TempDir Path dir) {
        Context root = Context.create();
        root.plugin(new FsPlugin(), null).await().join();
        root.on(FsEvents.READ, (thisArg, args) -> {
            throw new FsException("denied by policy: " + args[0]);
        });
        FileSystemService fs = root.get(FileSystemService.NAME);

        assertThatThrownBy(() -> fs.readText(dir.resolve("secret.txt").toString()))
                .isInstanceOf(FsException.class)
                .hasMessageContaining("denied by policy");
        root.fiber().disposeAsync().join();
    }

    @Test
    void fsToolFamilyConsumesTheSeam(@TempDir Path dir) throws Exception {
        Context root = Context.create();
        root.plugin(new io.majo.harness.tools.ToolsPlugin(), null).await().join();
        root.plugin(new FsPlugin(), null).await().join();
        root.plugin(new FsToolPlugin(), null).await().join();
        ToolRegistry tools = root.get(ToolRegistry.NAME);
        assertThat(tools.specs()).extracting(spec -> spec.name())
                .containsExactlyInAnyOrder(
                        "read_file", "write_file", "edit_file", "glob", "grep", "git_status");

        Path file = dir.resolve("note.txt");
        Files.writeString(file, "payload");
        String arguments = MAPPER.writeValueAsString(Map.of("path", file.toString()));
        ToolResult ok = tools.execute(ToolCall.of("read_file", arguments));
        assertThat(ok.ok()).isTrue();
        assertThat(ok.content()).isEqualTo("payload");
        assertThat(ok.data()).containsKey("path");

        ToolResult missing = tools.execute(ToolCall.of("read_file",
                MAPPER.writeValueAsString(Map.of("path", dir.resolve("gone.txt").toString()))));
        assertThat(missing.ok()).isFalse();
        assertThat(missing.visibleText()).contains("cannot read");

        // write_file creates content (and parent directories)
        Path created = dir.resolve("deep").resolve("made.txt");
        ToolResult written = tools.execute(ToolCall.of("write_file", MAPPER.writeValueAsString(
                Map.of("path", created.toString(), "content", "alpha beta gamma"))));
        assertThat(written.ok()).isTrue();
        assertThat(created).hasContent("alpha beta gamma");

        // edit_file: unique replace, not-found and not-unique failures
        ToolResult edited = tools.execute(ToolCall.of("edit_file", MAPPER.writeValueAsString(
                Map.of("path", created.toString(), "old_string", "beta", "new_string", "BETA"))));
        assertThat(edited.ok()).isTrue();
        assertThat(created).hasContent("alpha BETA gamma");
        ToolResult notFound = tools.execute(ToolCall.of("edit_file", MAPPER.writeValueAsString(
                Map.of("path", created.toString(), "old_string", "delta", "new_string", "x"))));
        assertThat(notFound.ok()).isFalse();
        assertThat(notFound.visibleText()).contains("not found");

        Path many = dir.resolve("many.txt");
        Files.writeString(many, "one two one two one\n");
        ToolResult notUnique = tools.execute(ToolCall.of("edit_file", MAPPER.writeValueAsString(
                Map.of("path", many.toString(), "old_string", "one", "new_string", "X"))));
        assertThat(notUnique.ok()).isFalse();
        assertThat(notUnique.visibleText()).contains("3 times");
        ToolResult replaceAll = tools.execute(ToolCall.of("edit_file", MAPPER.writeValueAsString(
                Map.of("path", many.toString(), "old_string", "one", "new_string", "X",
                        "replace_all", true))));
        assertThat(replaceAll.ok()).isTrue();
        assertThat(replaceAll.data()).containsEntry("replacements", 3);
        assertThat(many).hasContent("X two X two X\n");

        // glob: recursive listing with truncation metadata
        ToolResult found = tools.execute(ToolCall.of("glob", MAPPER.writeValueAsString(
                Map.of("root", dir.toString(), "pattern", "**/*.txt"))));
        assertThat(found.ok()).isTrue();
        assertThat(found.content()).contains("made.txt");

        // grep: hits plus match count
        ToolResult hits = tools.execute(ToolCall.of("grep", MAPPER.writeValueAsString(
                Map.of("path", dir.toString(), "pattern", "BETA"))));
        assertThat(hits.ok()).isTrue();
        assertThat(hits.content()).contains("made.txt:1:alpha BETA");
        assertThat(hits.data()).containsEntry("matches", 1);
        root.fiber().disposeAsync().join();
    }
}
