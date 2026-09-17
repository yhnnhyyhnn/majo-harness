package io.majo.harness.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jcordis.core.context.Context;
import io.jcordis.core.logger.ConsoleExporter;
import io.majo.harness.boot.HarnessBoot;
import io.majo.harness.headless.CalculatorToolPlugin;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generative tool catalog (dsh docs/tool-catalog.md + verify pairing):
 * boots the shipped offline profile (web-mock — the same tool surface that
 * ships) and asserts that {@code docs/tool-catalog.md} matches the live
 * {@link ToolRegistry}. A tool change without regenerating the doc fails the
 * build; {@code -Dmajo.gen.tool-catalog=true} (scripts/gen-tool-catalog.sh)
 * regenerates instead of asserting.
 */
class ToolCatalogTest {

    private static final Path DOC = Path.of("..", "docs", "tool-catalog.md");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @org.junit.jupiter.api.Timeout(120)
    void toolCatalogMatchesTheShippedToolSurface(@TempDir Path home) throws Exception {
        HarnessBoot boot = bootWebMock(home);
        try {
            ToolRegistry tools = boot.ctx().get(ToolRegistry.NAME);
            List<ToolSpec> specs = tools.specs().stream()
                    .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                    .toList();
            assertThat(specs).isNotEmpty();
            String rendered = render(specs);

            if (Boolean.getBoolean("majo.gen.tool-catalog")) {
                Files.writeString(DOC, rendered, StandardCharsets.UTF_8);
                return;
            }
            assertThat(DOC.toFile().exists())
                    .as("docs/tool-catalog.md missing — run bash scripts/gen-tool-catalog.sh")
                    .isTrue();
            String committed = Files.readString(DOC, StandardCharsets.UTF_8);
            assertThat(rendered)
                    .as("docs/tool-catalog.md is stale — run bash scripts/gen-tool-catalog.sh")
                    .isEqualTo(committed);
        } finally {
            boot.dispose();
        }
    }

    /** Boots the packaged {@code web-mock.yml} with file paths moved into {@code home}. */
    private static HarnessBoot bootWebMock(Path home) throws Exception {
        String profileText;
        try (InputStream stream = ToolCatalogTest.class.getClassLoader()
                .getResourceAsStream("web-mock.yml")) {
            assertThat(stream).as("web-mock.yml packaged").isNotNull();
            profileText = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String sessionsDir = home.resolve("sessions").toString().replace('\\', '/');
        String settingsFile = home.resolve("settings.json").toString().replace('\\', '/');
        profileText = profileText
                .replace("~/.majo-harness/web/sessions", sessionsDir)
                .replace("~/.majo-harness/web/settings.json", settingsFile);
        Path profile = home.resolve("web-mock.yml");
        Files.writeString(profile, profileText);

        Context root = Context.create();
        new ConsoleExporter(root);
        HarnessBoot boot = new HarnessBoot(root)
                .register(CalculatorToolPlugin.NAME, new CalculatorToolPlugin());
        boot.launch(boot.readProfileText(Files.readString(profile), "web-mock.yml"));
        return boot;
    }

    /** Renders the deterministic catalog markdown for the given specs. */
    static String render(List<ToolSpec> specs) {
        StringBuilder out = new StringBuilder();
        out.append("# Tool catalog\n\n");
        out.append("<!-- GENERATED from the shipped offline profile's ToolRegistry")
                .append(" by ToolCatalogTest — do not edit by hand. ")
                .append("Regenerate: bash scripts/gen-tool-catalog.sh -->\n\n");
        out.append(specs.size()).append(" tools on the shipped `web-mock` profile.\n\n");
        out.append("| tool | description | parameters |\n|---|---|---|\n");
        for (ToolSpec spec : specs) {
            out.append("| `").append(spec.name()).append("` | ")
                    .append(mdEscape(spec.description())).append(" | ")
                    .append(mdEscape(parametersJson(spec))).append(" |\n");
        }
        out.append("\nEvery tool call flows the same guard pipeline "
                + "(`tools/pre-execute` policy → approval gate → execute) before its "
                + "durable `TOOL_RESULT` lands in the session log.\n");
        return out.toString();
    }

    private static String parametersJson(ToolSpec spec) {
        try {
            return spec.parameters() == null ? "{}"
                    : JSON.writeValueAsString(spec.parameters());
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String mdEscape(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }
}
