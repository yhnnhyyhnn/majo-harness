package io.majo.harness.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileReferencesServiceTest {

    private static FileReferencesService mount(Path cwd) {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        return new FileReferencesService(ctx, ctx.get(SessionService.NAME), cwd);
    }

    @Test
    void suggestWalksWorkspaceSkippingNoise(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/App.java"), "class App {}");
        Files.writeString(dir.resolve("README.md"), "readme");
        Files.createDirectories(dir.resolve("node_modules/pkg"));
        Files.writeString(dir.resolve("node_modules/pkg/index.js"), "noise");

        FileReferencesService references = mount(dir);
        List<FileReferencesService.Suggestion> hits = references.suggest("");
        assertThat(hits).extracting(FileReferencesService.Suggestion::path)
                .containsExactlyInAnyOrder("README.md", "src/App.java");
        assertThat(references.suggest("app").stream()
                .map(FileReferencesService.Suggestion::path))
                .containsExactly("src/App.java");
    }

    @Test
    void injectAppendsDurableNoteAndTruncates(@TempDir Path dir) throws Exception {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        SessionService sessions = ctx.get(SessionService.NAME);
        FileReferencesService references = new FileReferencesService(ctx, sessions, dir);

        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "the answer is 42");
        String sessionId = sessions.createSession();
        int injected = references.inject(sessionId, file.toString());

        assertThat(injected).isGreaterThan(0);
        SessionEvent note = sessions.events(sessionId).stream()
                .filter(event -> event.type() == SessionEventType.CONTEXT_NOTE)
                .findFirst().orElseThrow();
        assertThat(note.content()).startsWith(ContextPlugin.MARKER + "@" + file)
                .contains("the answer is 42");

        // oversized content truncates at MAX_CHARS
        Files.writeString(file, "z".repeat(FileReferencesService.MAX_CHARS + 5_000));
        String other = sessions.createSession();
        references.inject(other, file.toString());
        SessionEvent big = sessions.events(other).stream()
                .filter(event -> event.type() == SessionEventType.CONTEXT_NOTE)
                .findFirst().orElseThrow();
        assertThat(big.content()).contains(
                "truncated at " + FileReferencesService.MAX_CHARS + " chars");
    }

    @Test
    void binaryAndMissingFilesFailLoud(@TempDir Path dir) throws Exception {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        SessionService sessions = ctx.get(SessionService.NAME);
        FileReferencesService references = new FileReferencesService(ctx, sessions, dir);

        Path binary = dir.resolve("blob.bin");
        Files.write(binary, new byte[] {1, 0, 2, 0, 3});
        String sessionId = sessions.createSession();
        assertThatThrownBy(() -> references.inject(sessionId, binary.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binary");
        assertThatThrownBy(() -> references.inject(sessionId, "missing.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a file");
        assertThatThrownBy(() -> references.inject(sessionId, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sessions.events(sessionId)).isEmpty();
    }
}
