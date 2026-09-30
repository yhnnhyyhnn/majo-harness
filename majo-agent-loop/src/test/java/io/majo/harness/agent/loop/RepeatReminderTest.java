package io.majo.harness.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jcordis.core.context.Context;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolSpec;
import io.majo.harness.tools.ToolsPlugin;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deepened repeat-call reminder (dsh guard repeat-tool-reminder parity):
 * per-session chains, semantically-identical argument matching (deep
 * key-sorted), denied calls count too (and the denial carries the advisory),
 * and a human turn opening resets the chain — goal rounds do not.
 */
class RepeatReminderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Context boot(boolean enabled) {
        Context ctx = Context.create();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new RepeatReminderPlugin(enabled), null).await().join();
        return ctx;
    }

    private static void registerCounter(ToolRegistry tools, String name) {
        tools.register(new Tool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.of(name, "returns its arguments");
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.ok("ran:" + call.arguments());
            }
        });
    }

    @Test
    void identicalRepeatsGetTheAdvisoryRegardlessOfKeyOrderOrWhitespace() {
        Context ctx = boot(true);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        registerCounter(tools, "probe");

        ToolResult first = tools.execute(ToolCall.of("probe", "{\"q\": 1, \"mode\": \"fast\"}"));
        assertThat(first.content()).doesNotContain("advisory");
        // same call, different key order and spacing: still semantically identical
        ToolResult second = tools.execute(ToolCall.of("probe",
                "{ \"mode\" : \"fast\" , \"q\" : 1 }"));
        assertThat(second.content()).contains("identical repeat call #2");
        ToolResult third = tools.execute(ToolCall.of("probe", "{\"q\":1,\"mode\":\"fast\"}"));
        assertThat(third.content()).contains("identical repeat call #3");
        // different arguments: new chain
        ToolResult different = tools.execute(ToolCall.of("probe", "{\"q\":2,\"mode\":\"fast\"}"));
        assertThat(different.content()).doesNotContain("advisory");
    }

    @Test
    void chainsArePerSession() {
        Context ctx = boot(true);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        registerCounter(tools, "probe");
        String args = "{\"q\":1}";

        ToolResult sessionA = InteractionContext.runSession("session-a", () ->
                tools.execute(ToolCall.of("probe", args)));
        assertThat(sessionA.content()).doesNotContain("advisory");
        // a different session's identical call is repeat #1 of ITS OWN chain
        ToolResult sessionB = InteractionContext.runSession("session-b", () ->
                tools.execute(ToolCall.of("probe", args)));
        assertThat(sessionB.content()).doesNotContain("advisory");
        // back to session a: repeat #2 of that chain
        ToolResult againA = InteractionContext.runSession("session-a", () ->
                tools.execute(ToolCall.of("probe", args)));
        assertThat(againA.content()).contains("identical repeat call #2");
    }

    @Test
    void deniedCallsCountAndCarryTheAdvisory() {
        Context ctx = boot(true);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        registerCounter(tools, "probe");
        // a policy plugin denies probe calls (registered AFTER the reminder:
        // the reminder wraps whatever the rest of the chain returns)
        ctx.on(io.majo.harness.tools.ToolEvents.PRE_EXECUTE, (thisArg, args) -> {
            ToolCall call = (ToolCall) args[0];
            @SuppressWarnings("unchecked")
            java.util.function.Supplier<Object> next =
                    (java.util.function.Supplier<Object>) args[args.length - 1];
            if (call.name().equals("probe")) {
                return ToolResult.error("denied by policy");
            }
            return next.get();
        });
        String args = "{\"q\":1}";

        ToolResult first = tools.execute(ToolCall.of("probe", args));
        assertThat(first.ok()).isFalse();
        assertThat(first.error()).doesNotContain("advisory");
        // the identical denied retry counts AND its denial carries the reminder
        ToolResult second = tools.execute(ToolCall.of("probe", args));
        assertThat(second.ok()).isFalse();
        assertThat(second.error()).contains("denied by policy", "identical repeat call #2");
    }

    @Test
    void humanTurnOpenResetsTheChainButGoalRoundsDoNot() {
        Context ctx = boot(true);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        registerCounter(tools, "probe");
        String sessionId = "s1";
        String args = "{\"q\":1}";

        InteractionContext.runSession(sessionId, () -> tools.execute(ToolCall.of("probe", args)));
        // a goal round opens: no reset
        ctx.emit(AgentLoopEvents.TURN_OPENED,
                new Object[] {sessionId, "goal"});
        ToolResult inGoalRound = InteractionContext.runSession(sessionId, () ->
                tools.execute(ToolCall.of("probe", args)));
        assertThat(inGoalRound.content()).contains("identical repeat call #2");
        // a human turn opens: chain resets
        ctx.emit(AgentLoopEvents.TURN_OPENED,
                new Object[] {sessionId, null});
        ToolResult afterHuman = InteractionContext.runSession(sessionId, () ->
                tools.execute(ToolCall.of("probe", args)));
        assertThat(afterHuman.content()).doesNotContain("advisory");
    }

    @Test
    void disabledPluginDoesNothing() {
        Context ctx = boot(false);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        registerCounter(tools, "probe");
        assertThat(tools.execute(ToolCall.of("probe", "{\"q\":1}")).content())
                .doesNotContain("advisory");
        assertThat(tools.execute(ToolCall.of("probe", "{\"q\":1}")).content())
                .doesNotContain("advisory");
    }

    @Test
    void canonicalFormIsOrderAndWhitespaceInsensitive() {
        assertThat(RepeatReminderPlugin.canonical("{\"b\":1,\"a\":\"x\"}"))
                .isEqualTo(RepeatReminderPlugin.canonical("{ \"a\" : \"x\" , \"b\" : 1 }"));
        assertThat(RepeatReminderPlugin.canonical("{\"a\":{\"d\":2,\"c\":[1,{\"e\":3}]}}"))
                .isEqualTo(RepeatReminderPlugin.canonical("{\"a\":{\"c\":[1,{\"e\":3}],\"d\":2}}"));
        assertThat(RepeatReminderPlugin.canonical("{\"q\":1}"))
                .isNotEqualTo(RepeatReminderPlugin.canonical("{\"q\":2}"));
        // unparseable arguments fall back to the raw stripped text
        assertThat(RepeatReminderPlugin.canonical("  raw text  ")).isEqualTo("raw text");
    }
}
