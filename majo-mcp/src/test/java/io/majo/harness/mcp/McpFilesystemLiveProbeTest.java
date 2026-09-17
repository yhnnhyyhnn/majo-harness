package io.majo.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jcordis.core.context.Context;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolsPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Live probe against the real ecosystem's filesystem MCP server
 * ({@code npx @modelcontextprotocol/server-filesystem}) — exercises the
 * client against a third-party implementation, not just the in-repo echo
 * server. Skipped unless {@code MAJO_MCP_PROBE=1}; CI runs it as a
 * best-effort step (continue-on-error), like the DuckDuckGo probe. Needs
 * node/npx on PATH and outbound network (the first run downloads the
 * package).
 */
@EnabledIfEnvironmentVariable(named = "MAJO_MCP_PROBE", matches = "1")
final class McpFilesystemLiveProbeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void realFilesystemServerHandshakesListsAndCalls() throws Exception {
        Path dir = Files.createTempDirectory("majo-mcp-probe");
        String marker = "majo-mcp live probe " + UUID.randomUUID();
        Files.writeString(dir.resolve("probe.txt"), marker);

        String npx = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "npx.cmd"
                : "npx";
        Context ctx = Context.create();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        // the first npx run may download the package: give the handshake a
        // generous timeout
        ctx.plugin(new McpPlugin(), Map.of(
                "requestTimeoutSeconds", 180,
                "servers", Map.of("fs", Map.of(
                        "command", npx,
                        "args", List.of("-y",
                                "@modelcontextprotocol/server-filesystem", dir.toString())))))
                .await().join();

        McpService mcp = ctx.get(McpService.NAME);
        assertThat(mcp.servers()).as("filesystem server connected").containsExactly("fs");

        List<String> toolNames = mcp.tools("fs").stream().map(McpConnection.ToolInfo::name)
                .toList();
        System.out.println("[mcp-live] tools: " + toolNames);
        assertThat(toolNames).as("ecosystem server tool list").isNotEmpty();

        // round-trip a read through the registry bridge when the server
        // offers a read tool (schema-accurate for the canonical filesystem
        // server; any future rename degrades this to the list assertion)
        toolNames.stream()
                .filter(name -> name.equals("read_file") || name.contains("read"))
                .findFirst()
                .ifPresentOrElse(readTool -> {
                    ToolRegistry tools = ctx.get(ToolRegistry.NAME);
                    String arguments;
                    try {
                        arguments = MAPPER.writeValueAsString(
                                MAPPER.createObjectNode().put("path",
                                        dir.resolve("probe.txt").toString()));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    ToolResult result = tools.execute(
                            ToolCall.of("mcp__fs__" + readTool, arguments));
                    System.out.println("[mcp-live] " + readTool + " -> "
                            + result.visibleText());
                    assertThat(result.ok()).as("bridge read succeeded").isTrue();
                    assertThat(result.content()).contains(marker);
                }, () -> System.out.println("[mcp-live] no read tool offered; skipped call"));

        // unmount tears the npx-spawned process tree down
        ctx.fiber().disposeAsync().join();
        assertThat(mcp.servers()).isEmpty();
    }
}
