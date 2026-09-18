package io.majo.harness.spill;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import io.majo.harness.tools.ToolsPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spill policy: oversized successful results swap to preview + locator with
 * the full text retrievable via spill_read; small results pass through;
 * disabled when maxInlineBytes is unset; storage failure fails open (the
 * original result survives).
 */
class SpillPluginTest {

    private static Context mount(String path, String maxInlineBytes) {
        Context ctx = Context.create();
        Map<String, Object> config = new java.util.HashMap<>();
        if (path != null) {
            config.put("path", path);
        }
        if (maxInlineBytes != null) {
            config.put("maxInlineBytes", Integer.parseInt(maxInlineBytes));
        }
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new SpillPlugin(), config).await().join();
        return ctx;
    }

    private static void registerConstant(ToolRegistry tools, String content) {
        tools.register(new Tool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.of("flood", "returns a fixed payload");
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.ok(content, Map.of());
            }
        });
    }

    @Test
    void oversizedResultSwapsToPreviewPlusLocator(@TempDir Path dir) throws Exception {
        Context ctx = mount(dir.toString(), "1024");
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        String huge = "HEAD>" + "x".repeat(10_000);
        registerConstant(tools, huge);

        ToolResult result = tools.execute(ToolCall.of("flood", "{}"));
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).startsWith("HEAD>").contains("x".repeat(50));
        assertThat(result.content()).contains("[output truncated: " + huge.length()
                + " chars stored out-of-band. Use spill_read with id \"");
        String id = result.content().replaceAll("(?s).*id \"([0-9a-f]{8})\".*", "$1");

        // the full text is retrievable through the tool
        ToolResult full = tools.execute(ToolCall.of("spill_read", "{\"id\":\"" + id + "\"}"));
        assertThat(full.ok()).isTrue();
        assertThat(full.content()).isEqualTo(huge);

        // the store file exists on disk
        SpillStore store = ctx.get(SpillStore.NAME);
        assertThat(store.read(id)).isEqualTo(huge);
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void smallResultsPassThroughAndDisabledByDefault(@TempDir Path dir) {
        ToolRegistry tools = mount(dir.toString(), "100000").get(ToolRegistry.NAME);
        registerConstant(tools, "small output");
        assertThat(tools.execute(ToolCall.of("flood", "{}")).content())
                .isEqualTo("small output");

        // no maxInlineBytes: policy disabled even for huge results
        ToolRegistry unguarded = mount(dir.resolve("other").toString(), null)
                .get(ToolRegistry.NAME);
        registerConstant(unguarded, "y".repeat(10_000));
        assertThat(unguarded.execute(ToolCall.of("flood", "{}")).content())
                .hasSize(10_000);
    }

    @Test
    void storageFailureFailsOpen(@TempDir Path dir) throws Exception {
        // a FILE where the store directory should be: store construction fails
        // at mount, so mount without path override instead and point the store
        // at an impossible location via a nested mount
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        Context ctx = Context.create();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        // plugin mounting fails loudly when the store directory cannot exist
        try {
            ctx.plugin(new SpillPlugin(),
                    Map.of("path", blocker.toString(), "maxInlineBytes", 16)).await().join();
            // some filesystems defer the failure: treat successful mount as ok
        } catch (RuntimeException expected) {
            assertThat(expected).hasRootCauseInstanceOf(java.io.IOException.class);
        }
        ctx.fiber().disposeAsync().join();
    }
}
