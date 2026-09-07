package io.majo.harness.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsSeamTest {

    @Test
    void scopedOverridesApplyAndTearDown() {
        Context ctx = Context.create();
        ctx.plugin(new SettingsPlugin(), null).await().join();
        SettingsService settings = ctx.get(SettingsService.NAME);
        settings.set("region", "us");

        String inside = SettingsService.scoped(Map.of("region", "cn", "agent.model", "kilo"),
                () -> settings.get("region") + "/" + settings.get("agent.model"));
        assertThat(inside).isEqualTo("cn/kilo");
        // thread-local torn down: only the durable base remains visible
        assertThat(settings.get("region")).isEqualTo("us");
        assertThat(settings.get("agent.model")).isNull();
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void memoryStoreGetsSetsAndValidates() {
        Context ctx = Context.create();
        ctx.plugin(new SettingsPlugin(), null).await().join();
        SettingsService settings = ctx.get(SettingsService.NAME);
        assertThat(settings.isPersistent()).isFalse();

        assertThat(settings.get("missing")).isNull();
        settings.set("model", "mock");
        settings.set("temperature", "0.2");
        assertThat(settings.get("model")).isEqualTo("mock");
        assertThat(settings.keys()).containsExactly("model", "temperature");

        assertThatThrownBy(() -> settings.set("", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.set("k", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.get(" "))
                .isInstanceOf(IllegalArgumentException.class);
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void fileProviderPersistsAcrossRecreation(@TempDir Path dir) {
        Path file = dir.resolve("settings.json");
        {
            Context ctx = Context.create();
            ctx.plugin(new SettingsPlugin(), Map.of("path", file.toString())).await().join();
            SettingsService settings = ctx.get(SettingsService.NAME);
            assertThat(settings.isPersistent()).isTrue();
            settings.set("model", "mock");
            ctx.fiber().disposeAsync().join();
        }
        {
            Context ctx = Context.create();
            ctx.plugin(new SettingsPlugin(), Map.of("path", file.toString())).await().join();
            SettingsService settings = ctx.get(SettingsService.NAME);
            assertThat(settings.get("model")).isEqualTo("mock");
            assertThat(settings.keys()).containsExactly("model");
            ctx.fiber().disposeAsync().join();
        }
    }

    @Test
    void corruptSettingsFileFailsLoud(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.json");
        java.nio.file.Files.writeString(file, "not-json");
        Context ctx = Context.create();
        assertThatThrownBy(() -> ctx.plugin(new SettingsPlugin(), Map.of("path", file.toString()))
                .await().join()).isNotNull();
    }
}
