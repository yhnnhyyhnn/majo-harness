package io.majo.harness.ptc;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolsPlugin;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PTC runtime end-to-end: real Node.js execution of model-written programs,
 * with error mapping and timeout.
 */
class PtcPluginTest {

    private static Context mount(String nodePath, int timeoutSeconds) {
        Context ctx = Context.create();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        var config = new java.util.HashMap<String, Object>();
        if (nodePath != null) {
            config.put("nodePath", nodePath);
        }
        config.put("timeoutSeconds", timeoutSeconds);
        ctx.plugin(new PtcPlugin(), config).await().join();
        return ctx;
    }

    @Test
    void runCodeExecutesJavaScriptAndReturnsOutput() {
        Context ctx = mount(null, 30);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);

        ToolResult result = tools.execute(ToolCall.of("run_code",
                "{\"code\":\"const a = [1,2,3]; console.log(a.reduce((s,n) => s+n, 0));\"}"));
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).isEqualTo("6");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void runCodeHandlesJsonTransformation() {
        Context ctx = mount(null, 30);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);

        ToolResult result = tools.execute(ToolCall.of("run_code",
                "{\"code\":\"const d=[{n:'a',v:3},{n:'b',v:1}];console.log(JSON.stringify(d.sort((a,b)=>b.v-a.v).map(x=>x.n)));\"}"));
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).isEqualTo("[\"a\",\"b\"]");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void syntaxErrorReturnsToolError() {
        Context ctx = mount(null, 30);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);

        ToolResult result = tools.execute(ToolCall.of("run_code",
                "{\"code\":\"this is not valid javascript !!!\"}"));
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("exit");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void blankCodeFailsLoud() {
        Context ctx = mount(null, 30);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);

        ToolResult result = tools.execute(ToolCall.of("run_code", "{\"code\":\"\"}"));
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("pass a code string");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void timeoutProducesClearError() {
        Context ctx = mount(null, 1);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);

        ToolResult result = tools.execute(ToolCall.of("run_code",
                "{\"code\":\"setTimeout(() => {}, 60_000);\"}"));
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("timed out");
        ctx.fiber().disposeAsync().join();
    }
}
