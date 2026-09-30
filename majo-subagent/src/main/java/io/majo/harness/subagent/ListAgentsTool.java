package io.majo.harness.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;

/**
 * {@code list_agents} (dsh tool-subagent-control parity): the live child
 * agents — session id, task, status, last answer preview — so the model can
 * pick a {@code send_message} target.
 */
public final class ListAgentsTool implements Tool {

    public static final String NAME = "list_agents";

    private final SubagentService subagent;

    public ListAgentsTool(SubagentService subagent) {
        this.subagent = subagent;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.of(NAME,
                "Lists the child agents you have delegated to (session id, task, status, "
                        + "last answer preview) — the session_id feeds send_message.");
    }

    @Override
    public ToolResult execute(ToolCall call) {
        StringBuilder text = new StringBuilder();
        for (SubagentService.AgentEntry entry : subagent.agents()) {
            text.append("- ").append(entry.childSessionId())
                    .append(" [").append(entry.status()).append("] ")
                    .append(entry.task());
            if (entry.lastAnswerPreview() != null) {
                text.append(" → ").append(entry.lastAnswerPreview());
            }
            text.append('\n');
        }
        String listing = text.toString();
        return ToolResult.ok(listing.isEmpty() ? "no child agents yet" : listing.stripTrailing(),
                java.util.Map.of("count", subagent.agents().size()));
    }
}
