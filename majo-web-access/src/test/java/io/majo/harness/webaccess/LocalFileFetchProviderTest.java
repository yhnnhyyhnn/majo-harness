package io.majo.harness.webaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline fetch backend: serves only files under its configured root. */
final class LocalFileFetchProviderTest {

    @TempDir
    Path dir;

    @Test
    void servesFilesUnderRootAsText() throws Exception {
        Files.writeString(dir.resolve("hello-page.txt"), "hello offline\nworld", StandardCharsets.UTF_8);
        LocalFileFetchProvider provider = new LocalFileFetchProvider(dir);
        WebFetchResult result = provider.fetch(new WebFetchRequest("file:hello-page.txt"));
        assertThat(result.title()).isEqualTo("hello-page.txt");
        assertThat(result.text()).isEqualTo("hello offline\nworld");
        // plain relative names work too
        assertThat(provider.fetch(new WebFetchRequest("hello-page.txt")).text())
                .isEqualTo("hello offline\nworld");
    }

    @Test
    void rejectsTraversalAndAbsolutePaths() {
        LocalFileFetchProvider provider = new LocalFileFetchProvider(dir);
        assertThatThrownBy(() -> provider.fetch(new WebFetchRequest("file:../secret.txt")))
                .isInstanceOf(WebAccessException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> provider.fetch(new WebFetchRequest("file:/etc/passwd")))
                .isInstanceOf(WebAccessException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void missingFilesFailLoudly() {
        LocalFileFetchProvider provider = new LocalFileFetchProvider(dir);
        assertThatThrownBy(() -> provider.fetch(new WebFetchRequest("file:nope.txt")))
                .isInstanceOf(WebAccessException.class)
                .hasMessageContaining("no such demo file");
    }
}
