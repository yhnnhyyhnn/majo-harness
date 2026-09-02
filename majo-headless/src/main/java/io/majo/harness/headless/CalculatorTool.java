package io.majo.harness.headless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sample tool contributed by the headless app to demonstrate the tools seam.
 * It evaluates one integer expression such as {@code 1+2} or {@code 3*4} from
 * a {@code {"expression": "..."}} argument. Real capabilities (shell, fs, …)
 * arrive as capability-seam modules on the roadmap.
 */
public final class CalculatorTool implements Tool {

    public static final String NAME = "calc";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern EXPRESSION =
            Pattern.compile("^\\s*(-?\\d+)\\s*([+\\-*/])\\s*(-?\\d+)\\s*$");

    private static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Evaluates a simple integer arithmetic expression of the form a+b, a-b, a*b, or a/b.",
            schema());

    private static JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("expression").put("type", "string");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("expression");
        return schema;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String expression = null;
        try {
            JsonNode arguments = MAPPER.readTree(call.arguments());
            if (arguments != null && arguments.get("expression") != null) {
                expression = arguments.get("expression").asText();
            }
        } catch (Exception e) {
            return ToolResult.error("calc: cannot parse arguments: " + e.getMessage());
        }
        if (expression == null) {
            return ToolResult.error("calc: missing \"expression\" argument");
        }
        Matcher matcher = EXPRESSION.matcher(expression);
        if (!matcher.matches()) {
            return ToolResult.error("calc: cannot parse expression \"" + expression + "\"");
        }
        long left = Long.parseLong(matcher.group(1));
        long right = Long.parseLong(matcher.group(3));
        long value;
        switch (matcher.group(2).charAt(0)) {
            case '+' -> value = left + right;
            case '-' -> value = left - right;
            case '*' -> value = left * right;
            case '/' -> {
                if (right == 0) {
                    return ToolResult.error("calc: division by zero");
                }
                value = left / right;
            }
            default -> throw new IllegalStateException("unreachable operator");
        }
        return ToolResult.ok(Long.toString(value));
    }
}
