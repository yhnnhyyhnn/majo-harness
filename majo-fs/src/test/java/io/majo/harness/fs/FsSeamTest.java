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

        assertThatThrownBy(() -> fs.readText(dir.resolve("missing.txt").toString()))
                .isInstanceOf(FsException.class)
                .hasMessageContaining("cannot read");
        assertThatThrownBy(() -> fs.readText(dir.toString()))
                .isInstanceOf(FsException.class)
                .hasMessageContaining("directory");
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
    void readFileToolConsumesTheSeam(@TempDir Path dir) throws Exception {
        Context root = Context.create();
        root.plugin(new io.majo.harness.tools.ToolsPlugin(), null).await().join();
        root.plugin(new FsPlugin(), null).await().join();
        root.plugin(new FsToolPlugin(), null).await().join();
        ToolRegistry tools = root.get(ToolRegistry.NAME);
        assertThat(tools.specs()).extracting(spec -> spec.name()).containsExactly("read_file");

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
        root.fiber().disposeAsync().join();
    }
}
