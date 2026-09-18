package io.majo.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jcordis.core.context.Context;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolsPlugin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Live probe against a real remote MCP server over Streamable HTTP:
 * {@code https://mcp.deepwiki.com/mcp} — public, no auth. Exercises the HTTP
 * transport against a third-party hosted implementation, not just the
 * in-process fixture. Skipped unless {@code MAJO_MCP_PROBE=1}; CI runs it
 * best-effort (continue-on-error). Needs outbound network; a remote service
 * changing its tool surface degrades this to the list assertion.
 */
@EnabledIfEnvironmentVariable(named = "MAJO_MCP_PROBE", matches = "1")
final class McpRemoteLiveProbeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void remoteHttpServerHandshakesListsAndCalls() {
        Context ctx = Context.create();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new McpPlugin(), Map.of(
                "requestTimeoutSeconds", 60,
                "servers", Map.of("deepwiki", Map.of(
                        "url", "https://mcp.deepwiki.com/mcp"))))
                .await().join();
        try {
            McpService mcp = ctx.get(McpService.NAME);
            assertThat(mcp.servers()).as("remote server connected").containsExactly("deepwiki");

            List<String> toolNames = mcp.tools("deepwiki").stream()
                    .map(McpConnection.ToolInfo::name)
                    .toList();
            System.out.println("[mcp-remote] tools: " + toolNames);
            assertThat(toolNames).as("hosted tool list").isNotEmpty();

            toolNames.stream()
                    .filter(name -> name.equals("read_wiki_structure")
                            || name.contains("read_wiki"))
                    .findFirst()
                    .ifPresentOrElse(tool -> {
                        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
                        String arguments;
                        try {
                            arguments = MAPPER.writeValueAsString(
                                    MAPPER.createObjectNode().put("repoName", "facebook/react"));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        ToolResult result = tools.execute(
                                ToolCall.of("mcp__deepwiki__" + tool, arguments));
                        System.out.println("[mcp-remote] " + tool + " -> "
                                + result.visibleText());
                        assertThat(result.ok()).as("remote call succeeded").isTrue();
                        assertThat(result.content()).isNotBlank();
                    }, () -> System.out.println(
                            "[mcp-remote] no wiki tool offered; skipped call"));
        } finally {
            ctx.fiber().disposeAsync().join();
        }
    }
}
