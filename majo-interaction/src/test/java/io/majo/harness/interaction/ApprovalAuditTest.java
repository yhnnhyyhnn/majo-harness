package io.majo.harness.interaction;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionService;
import io.majo.harness.settings.SettingsPlugin;
import io.majo.harness.settings.SettingsService;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import io.majo.harness.tools.ToolsPlugin;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The durable approval audit pair (dsh user-approval): every gated call
 * running inside a session-bound turn leaves APPROVAL_REQUESTED +
 * APPROVAL_DECIDED in that session's log, and the session policy
 * (ask/never/auto) decides before any handler — audited with source=policy.
 */
class ApprovalAuditTest {

    private static final class DemoTool implements Tool {
        boolean executed;

        @Override
        public ToolSpec spec() {
            return ToolSpec.of("demo", "demo tool");
        }

        @Override
        public ToolResult execute(ToolCall call) {
            executed = true;
            return ToolResult.ok("ran");
        }
    }

    /** Mounts session + tools + interactions(deny handler) + approval gate. */
    private static Context harness(Map<Object, Object> approvalConfig) {
        Context root = Context.create();
        root.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        root.plugin(new ToolsPlugin(), null).await().join();
        root.plugin(new InteractionPlugin(), Map.of("approval", "deny")).await().join();
        root.plugin(new ToolApprovalPlugin(), approvalConfig).await().join();
        return root;
    }

    private static ToolResult executeGated(Context root, ToolRegistry tools,
            String sessionId, DemoTool tool) {
        // the loop binds the session around each turn; simulate that here
        return InteractionContext.runSession(sessionId, () -> tools.execute(ToolCall.of("demo", "{}")));
    }

    @Test
    void deniedHandlerCallLeavesAuditPairInTheSessionLog() {
        Context root = harness(Map.of("tools", List.of("demo")));
        SessionService sessions = root.get(SessionService.NAME);
        ToolRegistry tools = root.get(ToolRegistry.NAME);
        DemoTool tool = new DemoTool();
        tools.register(tool);
        String sessionId = sessions.createSession();

        sessions.append(sessionId, SessionEventType.TURN_START, Map.of());
        ToolResult result = executeGated(root, tools, sessionId, tool);
        sessions.append(sessionId, SessionEventType.TURN_END, Map.of());

        assertThat(result.ok()).isFalse(); // deny handler decided
        assertThat(tool.executed).isFalse();

        List<SessionEventType> kinds = sessions.events(sessionId).stream()
                .map(SessionEvent::type).toList();
        // the audit pair is wrapped by the open turn
        assertThat(kinds).containsExactly(
                SessionEventType.TURN_START,
                SessionEventType.APPROVAL_REQUESTED,
                SessionEventType.APPROVAL_DECIDED,
                SessionEventType.TURN_END);

        SessionEvent asked = sessions.events(sessionId).get(1);
        SessionEvent decided = sessions.events(sessionId).get(2);
        String askId = String.valueOf(asked.fields().get(SessionEvent.FIELD_APPROVAL_ID));
        assertThat(asked.fields().get(SessionEvent.FIELD_SUMMARY)).isEqualTo("run tool \"demo\"");
        assertThat(decided.fields().get(SessionEvent.FIELD_APPROVAL_ID)).isEqualTo(askId);
        assertThat(decided.fields().get(SessionEvent.FIELD_DECISION)).isEqualTo("deny");
        assertThat(decided.fields().get(SessionEvent.FIELD_SOURCE)).isEqualTo("handler");
    }

    @Test
    void sessionPolicyNeverDeniesWithoutAsking(@TempDir Path temp) {
        Context root = Context.create();
        root.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        root.plugin(new ToolsPlugin(), null).await().join();
        root.plugin(new SettingsPlugin(), Map.of("path", temp.resolve("settings.json").toString())).await().join();
        root.plugin(new InteractionPlugin(), Map.of("approval", "auto")).await().join();
        root.plugin(new ToolApprovalPlugin(), Map.of("tools", List.of("demo"))).await().join();
        SessionService sessions = root.get(SessionService.NAME);
        SettingsService settings = root.get(SettingsService.NAME);
        ToolRegistry tools = root.get(ToolRegistry.NAME);
        DemoTool tool = new DemoTool();
        tools.register(tool);
        String sessionId = sessions.createSession();
        // never beats the auto approval handler: the policy denies first
        settings.set("session.approval." + sessionId, "never");

        ToolResult result = executeGated(root, tools, sessionId, tool);

        assertThat(result.ok()).isFalse();
        assertThat(tool.executed).isFalse();
        List<SessionEvent> events = sessions.events(sessionId);
        // one durable decision, no ask: policy denials never consult handlers
        assertThat(events.stream().map(SessionEvent::type).toList())
                .containsExactly(SessionEventType.APPROVAL_DECIDED);
        assertThat(events.get(0).fields().get(SessionEvent.FIELD_DECISION)).isEqualTo("deny");
        assertThat(events.get(0).fields().get(SessionEvent.FIELD_SOURCE)).isEqualTo("policy");
    }

    @Test
    void sessionPolicyAutoGrantsAndAudits(@TempDir Path temp) {
        Context root = Context.create();
        root.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        root.plugin(new ToolsPlugin(), null).await().join();
        root.plugin(new SettingsPlugin(), Map.of("path", temp.resolve("settings.json").toString())).await().join();
        root.plugin(new InteractionPlugin(), Map.of("approval", "deny")).await().join();
        root.plugin(new ToolApprovalPlugin(), Map.of("tools", List.of("demo"))).await().join();
        SessionService sessions = root.get(SessionService.NAME);
        SettingsService settings = root.get(SettingsService.NAME);
        ToolRegistry tools = root.get(ToolRegistry.NAME);
        DemoTool tool = new DemoTool();
        tools.register(tool);
        String sessionId = sessions.createSession();
        // auto beats the deny handler: the policy grants without consulting it
        settings.set("session.approval." + sessionId, "auto");

        ToolResult result = executeGated(root, tools, sessionId, tool);

        assertThat(result.ok()).isTrue();
        assertThat(tool.executed).isTrue();
        List<SessionEvent> events = sessions.events(sessionId);
        assertThat(events.stream().map(SessionEvent::type).toList())
                .containsExactly(SessionEventType.APPROVAL_DECIDED);
        assertThat(events.get(0).fields().get(SessionEvent.FIELD_DECISION)).isEqualTo("allow");
        assertThat(events.get(0).fields().get(SessionEvent.FIELD_SOURCE)).isEqualTo("policy");
    }

    @Test
    void callsOutsideATurnStayAuditFree() {
        Context root = harness(Map.of("tools", List.of("demo")));
        SessionService sessions = root.get(SessionService.NAME);
        ToolRegistry tools = root.get(ToolRegistry.NAME);
        DemoTool tool = new DemoTool();
        tools.register(tool);
        String sessionId = sessions.createSession();

        // no runSession binding: headless-style call, nothing durable to write
        ToolResult result = tools.execute(ToolCall.of("demo", "{}"));

        assertThat(result.ok()).isFalse(); // still denied by the deny handler
        assertThat(tool.executed).isFalse();
        assertThat(sessions.events(sessionId)).isEmpty();
    }
}
