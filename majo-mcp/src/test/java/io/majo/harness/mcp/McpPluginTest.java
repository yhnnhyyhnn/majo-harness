package io.majo.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import io.majo.harness.tools.ToolsPlugin;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MCP client end-to-end against a real stdio server (the in-repo
 * {@link EchoMcpServerMain}, spawned as a process): handshake, tools/list
 * bridging into namespaced registry tools with verbatim schemas, tools/call
 * including the isError path, loud-but-non-fatal failed mounts, and
 * connection teardown on unmount.
 */
class McpPluginTest {

    /** Command/args that spawn the in-repo echo MCP server on this JVM's stack. */
    private static Map<String, Object> echoServer() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return Map.of(
                "command", java,
                "args", List.of("-cp",
                        System.getProperty("java.class.path"),
                        EchoMcpServerMain.class.getName()));
    }

    private static ToolSpec spec(ToolRegistry tools, String name) {
        return tools.specs().stream()
                .filter(spec -> spec.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void serverMountsToolsBridgeCallsFlowAndUnmountCloses() {
        Context ctx = Context.create();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new McpPlugin(), Map.of(
                "requestTimeoutSeconds", 15,
                "servers", Map.of(
                        "echo", echoServer(),
                        "broken", Map.of("command", "definitely-not-a-real-command-42"))))
                .await().join();
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        McpService service = ctx.get(McpService.NAME);

        // the healthy server is connected; the broken one failed loudly but
        // non-fatally (boot continued, the other server kept working)
        assertThat(service.servers()).containsExactly("echo");

        // the MCP tool is bridged into the registry under its namespace
        ToolSpec spec = spec(tools, "mcp__echo__echo");
        assertThat(spec.description()).isEqualTo("echoes the message back");
        assertThat(spec.parameters().path("type").asText()).isEqualTo("object");
        assertThat(spec.parameters().path("properties").path("message")
                .path("type").asText()).isEqualTo("string");

        // a call round-trips through the server process
        ToolResult ok = tools.execute(ToolCall.of("mcp__echo__echo", "{\"message\":\"hello\"}"));
        assertThat(ok.ok()).isTrue();
        assertThat(ok.content()).isEqualTo("echo: hello");

        // the server's isError result surfaces as an ordinary tool error
        ToolResult failed = tools.execute(ToolCall.of("mcp__echo__echo", "{\"message\":\"fail\"}"));
        assertThat(failed.ok()).isFalse();
        assertThat(failed.error()).contains("no dice");

        // unmount closes the connections (the echo server exits on stdin EOF)
        ctx.fiber().disposeAsync().join();
        assertThat(service.servers()).isEmpty();
    }

    @Test
    void envReferencesExpandByNameAndFailLoudWhenUnset() {
        Context ctx = Context.create();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        // broken server + unset env reference: both mount failures are loud,
        // non-fatal, and leave no tools behind
        ctx.plugin(new McpPlugin(), Map.of(
                "servers", Map.of(
                        "broken", Map.of("command", "definitely-not-a-real-command-42"),
                        "badenv", Map.of(
                                "command", "whatever",
                                "env", Map.of("TOKEN", "${MAJO_MCP_TEST_UNSET_VAR}")))))
                .await().join();

        McpService service = ctx.get(McpService.NAME);
        assertThat(service.servers()).isEmpty();
        ctx.fiber().disposeAsync().join();
    }
}
