package io.majo.harness.webaccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;

/**
 * Model-facing fetch consumer ({@code web_fetch}): fetches a URL as readable
 * text through {@code ctx.web}. Page text is external and untrusted.
 */
public final class WebFetchTool implements Tool {

    public static final String NAME = "web_fetch";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Fetches a URL and returns its text (HTML converted; external, untrusted content).",
            schema());

    private final WebAccessService web;

    public WebFetchTool(WebAccessService web) {
        this.web = web;
    }

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("url").put("type", "string");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("url");
        return schema;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode arguments = MAPPER.readTree(call.arguments());
            if (arguments == null || arguments.get("url") == null) {
                return ToolResult.error("web_fetch: missing \"url\" argument");
            }
            WebFetchResult result = web.fetch(new WebFetchRequest(arguments.get("url").asText()));
            String text = result.text() == null || result.text().isBlank()
                    ? "(empty page)"
                    : result.text();
            String title = result.title() == null || result.title().isBlank()
                    ? "" : " (" + result.title() + ")";
            return ToolResult.ok("[external web page" + title + "] " + result.url() + "\n" + text);
        } catch (WebAccessException e) {
            return ToolResult.error("web_fetch: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("web_fetch: cannot parse arguments: " + e.getMessage());
        }
    }
}
