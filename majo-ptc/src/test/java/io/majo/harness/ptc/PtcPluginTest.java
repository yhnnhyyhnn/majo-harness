package io.majo.harness.ptc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jcordis.core.context.Context;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolsPlugin;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PTC runtime end-to-end: real Node.js execution of model-written programs —
 * the tools.<name>(args) callback protocol (JSON-lines control channel),
 * explicit result() vs the console.log fallback, tool-failure propagation,
 * the maxToolCalls cap, error mapping, and timeout.
 */
class PtcPluginTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        // console.log falls back to the answer when no explicit result() ran
        assertThat(result.content()).startsWith("6");
        assertThat(result.content()).contains("without calling result()");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void runCodeHandlesJsonTransformation() {
        Context ctx = mount(null, 30);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);

        ToolResult result = tools.execute(ToolCall.of("run_code",
                "{\"code\":\"const d=[{n:'a',v:3},{n:'b',v:1}];console.log(JSON.stringify(d.sort((a,b)=>b.v-a.v).map(x=>x.n)));\"}"));
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).startsWith("[\"a\",\"b\"]");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void explicitResultBeatsThePrintedFallback() throws Exception {
        Context ctx = mount(null, 30);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        String code = "console.log('side effect'); result({final: 7});";
        ToolResult result = tools.execute(ToolCall.of("run_code",
                MAPPER.writeValueAsString(Map.of("code", code))));
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).contains("\"final\" : 7");
        assertThat(result.content()).doesNotContain("side effect");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void programCallsHostToolsThroughTheCallbackProtocol() throws Exception {
        Context ctx = mount(null, 30);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        tools.register(new io.majo.harness.tools.Tool() {
            @Override
            public io.majo.harness.tools.ToolSpec spec() {
                return io.majo.harness.tools.ToolSpec.of("echo_json", "returns structured data");
            }

            @Override
            public io.majo.harness.tools.ToolResult execute(ToolCall call) {
                return io.majo.harness.tools.ToolResult.ok("{\"answer\": 21}");
            }
        });

        String code = "const data = await tools.echo_json({x: 1}); result({double: data.answer * 2});";
        ToolResult result = tools.execute(ToolCall.of("run_code",
                MAPPER.writeValueAsString(Map.of("code", code))));
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).contains("42");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void plainTextToolContentArrivesAsAString() throws Exception {
        Context ctx = mount(null, 30);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        tools.register(new io.majo.harness.tools.Tool() {
            @Override
            public io.majo.harness.tools.ToolSpec spec() {
                return io.majo.harness.tools.ToolSpec.of("plain", "returns text");
            }

            @Override
            public io.majo.harness.tools.ToolResult execute(ToolCall call) {
                return io.majo.harness.tools.ToolResult.ok("hello from host");
            }
        });

        String code = "result('got: ' + await tools.plain());";
        ToolResult result = tools.execute(ToolCall.of("run_code",
                MAPPER.writeValueAsString(Map.of("code", code))));
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).isEqualTo("got: hello from host");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void toolFailurePropagatesIntoTheProgramAsARejection() throws Exception {
        Context ctx = mount(null, 30);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        tools.register(new io.majo.harness.tools.Tool() {
            @Override
            public io.majo.harness.tools.ToolSpec spec() {
                return io.majo.harness.tools.ToolSpec.of("boom", "always fails");
            }

            @Override
            public io.majo.harness.tools.ToolResult execute(ToolCall call) {
                return io.majo.harness.tools.ToolResult.error("exploded on purpose");
            }
        });

        // caught: the program sees the rejection and completes normally
        String caught = "try { await tools.boom({}); result('unreachable'); }"
                + " catch (e) { result('caught: ' + e.message); }";
        ToolResult handled = tools.execute(ToolCall.of("run_code",
                MAPPER.writeValueAsString(Map.of("code", caught))));
        assertThat(handled.ok()).isTrue();
        assertThat(handled.content()).isEqualTo("caught: exploded on purpose");

        // uncaught: the run_code call itself errors with the tool's message
        String uncaught = "await tools.boom({});";
        ToolResult unhandled = tools.execute(ToolCall.of("run_code",
                MAPPER.writeValueAsString(Map.of("code", uncaught))));
        assertThat(unhandled.ok()).isFalse();
        assertThat(unhandled.error()).contains("exploded on purpose");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void maxToolCallsCapsRunawayPrograms() throws Exception {
        Context ctx = Context.create();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new PtcPlugin(), Map.of("timeoutSeconds", 30, "maxToolCalls", 2)).await().join();
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        tools.register(new io.majo.harness.tools.Tool() {
            @Override
            public io.majo.harness.tools.ToolSpec spec() {
                return io.majo.harness.tools.ToolSpec.of("noop", "does nothing");
            }

            @Override
            public io.majo.harness.tools.ToolResult execute(ToolCall call) {
                return io.majo.harness.tools.ToolResult.ok("ok");
            }
        });

        String code = "for (let i = 0; i < 5; i++) { await tools.noop({}); } result('done');";
        ToolResult result = tools.execute(ToolCall.of("run_code",
                MAPPER.writeValueAsString(Map.of("code", code))));
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("maxToolCalls");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void unknownToolCallSurfacesAsToolError() throws Exception {
        Context ctx = mount(null, 30);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        String code = "await tools.not_a_real_tool({});";
        ToolResult result = tools.execute(ToolCall.of("run_code",
                MAPPER.writeValueAsString(Map.of("code", code))));
        assertThat(result.ok()).isFalse();
        // the registry's unknown-tool failure rides back through the protocol
        assertThat(result.error()).contains("not_a_real_tool");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void syntaxErrorReturnsToolError() {
        Context ctx = mount(null, 30);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);

        ToolResult result = tools.execute(ToolCall.of("run_code",
                "{\"code\":\"this is not valid javascript !!!\"}"));
        assertThat(result.ok()).isFalse();
        // the shim reports the import failure as an error frame
        assertThat(result.error()).contains("ptc:");
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
                // a synchronous busy loop blocks top-level completion (a
                // pending timer would not — module completion ends the run)
                "{\"code\":\"const start = Date.now(); while (Date.now() - start < 60_000) {}\"}"));
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("timed out");
        ctx.fiber().disposeAsync().join();
    }
}
