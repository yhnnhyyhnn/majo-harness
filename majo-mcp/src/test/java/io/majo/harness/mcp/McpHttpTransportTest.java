package io.majo.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolsPlugin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Streamable HTTP transport (roadmap-0.5): profile rows with {@code url} +
 * {@code headers} mount remote servers — JSON and SSE response shapes, the
 * session id echoed on later calls, env-name header resolution, and the
 * capability-gated resources/prompts read-only tools.
 */
class McpHttpTransportTest {

    private HttpMcpFixture fixture;
    private Context ctx;

    private void mount(HttpMcpFixture fixtureToUse) {
        fixture = fixtureToUse;
        ctx = Context.create();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new io.majo.harness.session.SessionPlugin(),
                Map.of("store", "memory")).await().join();
        ctx.plugin(new io.majo.harness.session.SessionProjectionsPlugin(),
                null).await().join();
        ctx.plugin(new io.majo.harness.llm.LLMServicePlugin(),
                Map.of("defaultModel", "model")).await().join();
        ctx.plugin(new io.majo.harness.agent.loop.AgentLoopPlugin(), null).await().join();
        ctx.plugin(new McpPlugin(), Map.of(
                "requestTimeoutSeconds", 10,
                "servers", Map.of("httpd", Map.of(
                        "url", fixture.baseUrl(),
                        "headers", Map.of("Authorization", "Bearer test-token")))))
                .await().join();
    }

    @AfterEach
    void stop() {
        if (ctx != null) {
            ctx.fiber().disposeAsync().join();
        }
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void httpServerMountsToolsResourcesPromptsAndCalls() throws Exception {
        mount(new HttpMcpFixture());
        McpService mcp = ctx.get(McpService.NAME);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);

        assertThat(mcp.servers()).containsExactly("httpd");
        assertThat(fixture.lastAuth.get()).isEqualTo("Bearer test-token");
        assertThat(mcp.instructions("httpd"))
                .isEqualTo("Fixture usage: call upper with text.");

        ToolResult upper = tools.execute(ToolCall.of("mcp__httpd__upper", "{\"text\":\"abc\"}"));
        assertThat(upper.ok()).isTrue();
        assertThat(upper.content()).isEqualTo("ABC");
        // the session id from initialize was echoed on the call
        assertThat(fixture.sawSessionIdOnCall).isTrue();

        // dsh mcp-resources shape: shared tools with a server argument
        ToolResult resources = tools.execute(
                ToolCall.of("list_mcp_resources", "{}"));
        assertThat(resources.ok()).isTrue();
        assertThat(resources.content()).contains("[httpd]", "file:///probe.txt",
                "the probe resource");
        ToolResult templates = tools.execute(
                ToolCall.of("list_mcp_resource_templates", "{}"));
        assertThat(templates.content()).contains("file:///{key}");
        ToolResult read = tools.execute(ToolCall.of("read_mcp_resource",
                "{\"server\":\"httpd\",\"uri\":\"file:///probe.txt\"}"));
        assertThat(read.ok()).isTrue();
        assertThat(read.content()).isEqualTo("resource-body");

        ToolResult prompt = tools.execute(ToolCall.of("mcp__httpd__get_prompt",
                "{\"name\":\"greet\",\"arguments\":{\"who\":\"world\"}}"));
        assertThat(prompt.ok()).isTrue();
        assertThat(prompt.content()).isEqualTo("user: greet world");

        String description = tools.specs().stream()
                .filter(spec -> spec.name().equals("list_mcp_resources"))
                .findFirst().orElseThrow().description();
        assertThat(description).contains("server");
    }

    @Test
    void sseResponsesParse() throws Exception {
        HttpMcpFixture httpFixture = new HttpMcpFixture();
        httpFixture.sseResponses.set(true);
        mount(httpFixture);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        ToolResult upper = tools.execute(ToolCall.of("mcp__httpd__upper", "{\"text\":\"sse\"}"));
        assertThat(upper.ok()).isTrue();
        assertThat(upper.content()).isEqualTo("SSE");
    }

    private static void mountBase(Context local) {
        local.plugin(new ToolsPlugin(), null).await().join();
        local.plugin(new io.majo.harness.session.SessionPlugin(),
                Map.of("store", "memory")).await().join();
        local.plugin(new io.majo.harness.session.SessionProjectionsPlugin(),
                null).await().join();
        local.plugin(new io.majo.harness.llm.LLMServicePlugin(),
                Map.of("defaultModel", "model")).await().join();
        local.plugin(new io.majo.harness.agent.loop.AgentLoopPlugin(), null).await().join();
    }

    @Test
    void serverRowNeedsExactlyOneTransport() {
        Context local = Context.create();
        mountBase(local);
        // both url and command (or neither) is a loud, non-fatal mount failure
        local.plugin(new McpPlugin(), Map.of(
                "servers", Map.of("ambiguous", Map.of(
                        "url", "http://127.0.0.1:1/mcp",
                        "command", "whatever"))))
                .await().join();
        McpService service = local.get(McpService.NAME);
        assertThat(service == null || service.servers().isEmpty()).isTrue();
        local.fiber().disposeAsync().join();
    }
}
