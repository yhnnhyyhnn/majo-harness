package io.majo.harness.headless;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.FiberState;
import io.jcordis.loader.EntryOptions;
import io.majo.harness.boot.HarnessBoot;
import io.majo.harness.agent.loop.AgentLoopService;
import io.majo.harness.llm.ChatMessage;
import io.majo.harness.llm.ChatRequest;
import io.majo.harness.llm.LlmEvents;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.ToolRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end proof of the all-plugin vertical slice: a YAML profile names the
 * shipped plugins (session, tools, llm, mock provider, agent loop) plus an app
 * tool (calc); the loader activates them in dependency order and one run entry
 * drives a full tool-using turn that is reconstructable from the session log.
 */
class HeadlessIntegrationTest {

    private static String profileText() throws IOException {
        try (InputStream stream = HeadlessIntegrationTest.class.getClassLoader()
                .getResourceAsStream("headless.yml")) {
            if (stream == null) {
                throw new IOException("headless.yml missing from classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static HarnessBoot boot() throws IOException {
        Context root = Context.create();
        HarnessBoot boot = new HarnessBoot(root)
                .register(CalculatorToolPlugin.NAME, new CalculatorToolPlugin())
                .register(RunnerPlugin.NAME, new RunnerPlugin());
        List<EntryOptions> entries = new ArrayList<>(boot.readProfileText(profileText(), "headless.yml"));
        entries.add(HarnessBoot.entry(RunnerPlugin.NAME, RunnerPlugin.NAME, Map.of("task", "1+2")));
        return boot.launch(entries);
    }

    @Test
    void profileBootsAllPluginTreeAndRunsOneToolTurn() throws IOException {
        Context root = Context.create();
        List<ChatRequest> requests = new ArrayList<>();
        List<SessionEvent> broadcasts = new ArrayList<>();
        root.on(LlmEvents.REQUEST, (thisArg, args) -> {
            requests.add((ChatRequest) args[0]);
            return null;
        });
        root.on(SessionService.EVENT, (thisArg, args) -> {
            broadcasts.add((SessionEvent) args[1]);
            return null;
        });

        HarnessBoot boot = new HarnessBoot(root)
                .register(CalculatorToolPlugin.NAME, new CalculatorToolPlugin())
                .register(RunnerPlugin.NAME, new RunnerPlugin());
        List<EntryOptions> entries = new ArrayList<>(boot.readProfileText(profileText(), "headless.yml"));
        entries.add(HarnessBoot.entry(RunnerPlugin.NAME, RunnerPlugin.NAME, Map.of("task", "1+2")));
        boot.launch(entries);

        // every composed plugin is live in dependency order
        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_SESSION).state()).isEqualTo(FiberState.ACTIVE);
        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_SESSION_PROJECTIONS).state()).isEqualTo(FiberState.ACTIVE);
        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_TOOLS).state()).isEqualTo(FiberState.ACTIVE);
        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_LLM).state()).isEqualTo(FiberState.ACTIVE);
        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_LLM_MOCK).state()).isEqualTo(FiberState.ACTIVE);
        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_AGENT_LOOP).state()).isEqualTo(FiberState.ACTIVE);
        assertThat(boot.loader().expectFiber(CalculatorToolPlugin.NAME).state()).isEqualTo(FiberState.ACTIVE);
        assertThat(boot.loader().expectFiber(RunnerPlugin.NAME).state()).isEqualTo(FiberState.ACTIVE);

        SessionService sessions = boot.service(SessionService.NAME);
        assertThat(sessions.sessionIds()).hasSize(1);
        String sessionId = sessions.sessionIds().get(0);
        List<SessionEvent> events = sessions.events(sessionId);

        // durable log order mirrors the turn: markers, one request header per
        // model step, and the model-visible facts
        assertThat(events).extracting(SessionEvent::type).containsExactly(
                SessionEventType.TURN_START,
                SessionEventType.USER_MESSAGE,
                SessionEventType.REQUEST_HEADER,
                SessionEventType.ASSISTANT_MESSAGE, // tool round
                SessionEventType.TOOL_RESULT,
                SessionEventType.REQUEST_HEADER,
                SessionEventType.ASSISTANT_MESSAGE, // final answer
                SessionEventType.TURN_END);
        assertThat(events).extracting(SessionEvent::seq).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(broadcasts).hasSize(events.size());

        // headers record the request composition durably (model, system
        // prompt, offered tool names) ahead of each completion
        for (int i : new int[] {2, 5}) {
            SessionEvent header = events.get(i);
            assertThat(header.fields().get(SessionEvent.FIELD_MODEL)).isEqualTo("mock");
            assertThat(header.fields().get(SessionEvent.FIELD_SYSTEM_PROMPT))
                    .isEqualTo("You are a small calculator harness. Use the calc tool whenever asked for arithmetic.");
            assertThat(header.fields().get(SessionEvent.FIELD_TOOL_NAMES)).asList()
                    .containsExactly("calc");
        }

        SessionEvent toolRound = events.get(3);
        assertThat(toolRound.fields().get(SessionEvent.FIELD_TOOL_CALLS)).asList().hasSize(1);
        SessionEvent toolResult = events.get(4);
        assertThat(toolResult.fields().get(SessionEvent.FIELD_OK)).isEqualTo(true);
        assertThat(toolResult.content()).isEqualTo("3");
        assertThat(events.get(6).content()).isEqualTo("calculated: 3");

        // model-visible input was derived from the log: request 1 carries the
        // system prompt + user text + the calc schema; request 2 adds the
        // logged assistant round and its tool result, nothing else
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).messages()).extracting(ChatMessage::role)
                .containsExactly(
                        io.majo.harness.llm.ChatRole.SYSTEM,
                        io.majo.harness.llm.ChatRole.USER);
        assertThat(requests.get(0).tools()).extracting(spec -> spec.name()).containsExactly("calc");
        assertThat(requests.get(1).messages()).extracting(ChatMessage::role)
                .containsExactly(
                        io.majo.harness.llm.ChatRole.SYSTEM,
                        io.majo.harness.llm.ChatRole.USER,
                        io.majo.harness.llm.ChatRole.ASSISTANT,
                        io.majo.harness.llm.ChatRole.TOOL);
        assertThat(requests.get(1).messages().get(3).content()).isEqualTo("3");

        // typed projection state mirrors the log: the agent loop contributed
        // its turnSummary unit and hosts read typed state, not raw events
        io.majo.harness.session.SessionProjections projections = boot.service("sessionProjections");
        assertThat(projections.has(io.majo.harness.agent.loop.TurnSummary.KEY)).isTrue();
        io.majo.harness.agent.loop.TurnSummary.Summary summary =
                projections.<io.majo.harness.agent.loop.TurnSummary>require("turnSummary").summary(sessionId);
        assertThat(summary.turnOpen()).isFalse();
        assertThat(summary.turnCount()).isEqualTo(1);
        assertThat(summary.assistantRounds()).isEqualTo(2);
        assertThat(summary.toolCalls()).isEqualTo(1);
        assertThat(summary.lastUserText()).isEqualTo("1+2");
        assertThat(summary.lastFinalText()).isEqualTo("calculated: 3");

        // tool registry saw the app plugin's contribution
        ToolRegistry tools = boot.service(ToolRegistry.NAME);
        assertThat(tools.specs()).extracting(spec -> spec.name()).containsExactly("calc");

        boot.dispose();
        SessionService sessionsAfterDispose = root.get(SessionService.NAME);
        ToolRegistry toolsAfterDispose = root.get(ToolRegistry.NAME);
        assertThat(sessionsAfterDispose).isNull();
        assertThat(toolsAfterDispose).isNull();
    }

    @Test
    void removingAProviderDeactivatesItsDependentsAndReactivationReturns() throws IOException {
        HarnessBoot boot = boot();

        // session is a dependency of the agent loop, which is a dependency of
        // the run entry: removing the provider cascades through the tree —
        // dependents drop to PENDING with their effects reverted
        boot.loader().remove(HarnessBoot.PLUGIN_SESSION);
        boot.loader().await();

        SessionService sessionsAfterRemoval = boot.service(SessionService.NAME);
        AgentLoopService loopAfterRemoval = boot.service(AgentLoopService.NAME);
        ToolRegistry toolsAfterRemoval = boot.service(ToolRegistry.NAME);
        assertThat(sessionsAfterRemoval).isNull();
        assertThat(loopAfterRemoval).isNull();
        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_AGENT_LOOP).state())
                .isEqualTo(FiberState.PENDING);
        assertThat(boot.loader().expectFiber(RunnerPlugin.NAME).state()).isEqualTo(FiberState.PENDING);

        // independent capabilities keep running
        assertThat(toolsAfterRemoval).isNotNull();
        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_LLM_MOCK).state())
                .isEqualTo(FiberState.ACTIVE);

        // the provider returns: dependents auto-reactivate and their services
        // come back
        boot.loader().create(HarnessBoot.entry(HarnessBoot.PLUGIN_SESSION, HarnessBoot.PLUGIN_SESSION,
                Map.of("store", "memory")), null);
        boot.loader().await();
        SessionService sessionsAfterReturn = boot.service(SessionService.NAME);
        assertThat(sessionsAfterReturn).isNotNull();
        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_AGENT_LOOP).state())
                .isEqualTo(FiberState.ACTIVE);

        boot.dispose();
    }

    @Test
    void fsCapabilityRowsBootFromProfile(@TempDir java.nio.file.Path dir) throws IOException {
        String profile = """
                - id: tools
                  name: tools
                - id: fs
                  name: fs
                - id: fs-tools
                  name: fs-tools
                """;
        Context root = Context.create();
        HarnessBoot boot = new HarnessBoot(root);
        boot.launch(boot.readProfileText(profile, "fs.yml"));

        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_FS).state()).isEqualTo(FiberState.ACTIVE);
        io.majo.harness.fs.FileSystemService fs = boot.service("fs");
        java.nio.file.Path file = dir.resolve("hello.txt");
        fs.writeText(file.toString(), "hi");
        assertThat(fs.readText(file.toString())).isEqualTo("hi");

        io.majo.harness.tools.ToolRegistry tools = boot.service("tools");
        assertThat(tools.specs()).extracting(spec -> spec.name()).containsExactly("read_file");
        boot.dispose();
    }

    @Test
    void subprocessCapabilityRowsBootFromProfile() throws IOException {
        String profile = """
                - id: tools
                  name: tools
                - id: subprocess
                  name: subprocess
                - id: subprocess-tools
                  name: subprocess-tools
                """;
        Context root = Context.create();
        HarnessBoot boot = new HarnessBoot(root);
        boot.launch(boot.readProfileText(profile, "subprocess.yml"));

        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_SUBPROCESS).state()).isEqualTo(FiberState.ACTIVE);
        io.majo.harness.subprocess.SubprocessService subprocess = boot.service("subprocess");
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        io.majo.harness.subprocess.Command echo = (windows
                ? io.majo.harness.subprocess.Command.of("cmd", "/c", "echo", "boot-ok")
                : io.majo.harness.subprocess.Command.of("/bin/echo", "boot-ok"))
                .withTimeoutSeconds(30);
        assertThat(subprocess.run(echo).stdout()).contains("boot-ok");

        io.majo.harness.tools.ToolRegistry tools = boot.service("tools");
        assertThat(tools.specs()).extracting(spec -> spec.name()).containsExactly("run_command");
        boot.dispose();
    }

    @Test
    void shellCapabilityRowsBootFromProfile() throws IOException {
        String profile = """
                - id: tools
                  name: tools
                - id: subprocess
                  name: subprocess
                - id: shell
                  name: shell
                - id: shell-tools
                  name: shell-tools
                """;
        Context root = Context.create();
        HarnessBoot boot = new HarnessBoot(root);
        boot.launch(boot.readProfileText(profile, "shell.yml"));

        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_SHELL).state()).isEqualTo(FiberState.ACTIVE);
        io.majo.harness.shell.ShellService shell = boot.service("shell");
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        io.majo.harness.shell.ShellCommand echo = io.majo.harness.shell.ShellCommand.of(
                windows ? "Write-Output boot-ok" : "echo boot-ok").withTimeoutSeconds(30);
        assertThat(shell.run(echo).stdout()).contains("boot-ok");

        io.majo.harness.tools.ToolRegistry tools = boot.service("tools");
        assertThat(tools.specs()).extracting(spec -> spec.name()).containsExactly("run_shell");
        boot.dispose();
    }

    @Test
    void sandboxRowBootsFromProfile() throws IOException {
        String profile = """
                - id: sandbox
                  name: sandbox
                  config:
                    provider: identity
                """;
        Context root = Context.create();
        HarnessBoot boot = new HarnessBoot(root);
        boot.launch(boot.readProfileText(profile, "sandbox.yml"));

        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_SANDBOX).state()).isEqualTo(FiberState.ACTIVE);
        io.majo.harness.sandbox.SandboxService sandbox = boot.service("sandbox");
        assertThat(sandbox.provider().name()).isEqualTo("identity");
        assertThat(sandbox.confine(java.util.List.of("ls", "-la"))).containsExactly("ls", "-la");
        boot.dispose();
    }

    @Test
    void interactionRowsBootFromProfile() throws IOException {
        String profile = """
                - id: interactions
                  name: interactions
                  config:
                    approval: auto
                - id: tool-approval
                  name: tool-approval
                  config:
                    tools: [demo]
                """;
        Context root = Context.create();
        HarnessBoot boot = new HarnessBoot(root);
        boot.launch(boot.readProfileText(profile, "interaction.yml"));

        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_INTERACTIONS).state()).isEqualTo(FiberState.ACTIVE);
        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_TOOL_APPROVAL).state()).isEqualTo(FiberState.ACTIVE);
        io.majo.harness.interaction.InteractionService interactions = boot.service("interactions");
        assertThat(interactions.approve(io.majo.harness.interaction.ApprovalRequest.of("demo", "")))
                .isEqualTo(io.majo.harness.interaction.ApprovalDecision.APPROVE);
        boot.dispose();
    }

    @Test
    void skillRowsBootFromProfile(@TempDir java.nio.file.Path skillsDir) throws IOException {
        java.nio.file.Path alpha = skillsDir.resolve("alpha");
        java.nio.file.Files.createDirectories(alpha);
        java.nio.file.Files.writeString(alpha.resolve("SKILL.md"), "alpha instructions");
        String profile = """
                - id: tools
                  name: tools
                - id: skills
                  name: skills
                - id: skill-files
                  name: skill-files
                  config:
                    path: %s
                - id: skill-tools
                  name: skill-tools
                """.formatted(skillsDir.toString().replace("\\", "\\\\"));
        Context root = Context.create();
        HarnessBoot boot = new HarnessBoot(root);
        boot.launch(boot.readProfileText(profile, "skill.yml"));

        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_SKILLS).state()).isEqualTo(FiberState.ACTIVE);
        io.majo.harness.skill.SkillRegistry skills = boot.service("skills");
        assertThat(skills.skills()).extracting(s -> s.name()).containsExactly("alpha");
        io.majo.harness.tools.ToolRegistry tools = boot.service("tools");
        assertThat(tools.specs()).extracting(spec -> spec.name())
                .containsExactly("list_skills", "load_skill");
        boot.dispose();
    }

    @Test
    void subagentRowsDelegateInsideTheBootedTree() throws IOException {
        String profile = """
                - id: session
                  name: session
                - id: session-projections
                  name: session-projections
                - id: tools
                  name: tools
                - id: llm
                  name: llm
                  config:
                    defaultModel: mock
                - id: llm-mock
                  name: llm-mock
                - id: agent-loop
                  name: agent-loop
                - id: subagent
                  name: subagent
                - id: subagent-tools
                  name: subagent-tools
                - id: calc
                  name: calc
                """;
        Context root = Context.create();
        HarnessBoot boot = new HarnessBoot(root)
                .register(CalculatorToolPlugin.NAME, new CalculatorToolPlugin());
        boot.launch(boot.readProfileText(profile, "subagent.yml"));

        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_SUBAGENT).state()).isEqualTo(FiberState.ACTIVE);
        io.majo.harness.subagent.SubagentService subagent = boot.service("subagent");
        assertThat(subagent.delegate("1+2")).isEqualTo("calculated: 3");

        // the child turn produced its own durable session
        io.majo.harness.session.SessionService sessions = boot.service("sessions");
        assertThat(sessions.sessionIds()).hasSize(1);
        boot.dispose();
    }

    @Test
    void settingsAndCredentialsRowsBootFromProfile(@TempDir java.nio.file.Path dir) throws IOException {
        java.nio.file.Path envFile = dir.resolve(".env");
        java.nio.file.Files.writeString(envFile, "API_KEY=local-secret\n");
        String profile = """
                - id: settings
                  name: settings
                  config:
                    path: %s
                - id: credentials
                  name: credentials
                  config:
                    envFile: %s
                    sourceEnv: false
                """.formatted(
                dir.resolve("settings.json").toString().replace("\\", "\\\\"),
                envFile.toString().replace("\\", "\\\\"));
        Context root = Context.create();
        HarnessBoot boot = new HarnessBoot(root);
        boot.launch(boot.readProfileText(profile, "settings.yml"));

        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_SETTINGS).state()).isEqualTo(FiberState.ACTIVE);
        assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_CREDENTIALS).state()).isEqualTo(FiberState.ACTIVE);
        io.majo.harness.settings.SettingsService settings = boot.service("settings");
        settings.set("ui.language", "zh");
        assertThat(settings.get("ui.language")).isEqualTo("zh");
        io.majo.harness.credentials.CredentialsService credentials = boot.service("credentials");
        assertThat(credentials.resolve("API_KEY")).isEqualTo("local-secret");
        boot.dispose();
    }

    @Test
    void profileNamingAnUnknownPluginFailsLoud() {
        Context root = Context.create();
        HarnessBoot boot = new HarnessBoot(root);
        String profile = """
                - id: ghost
                  name: no-such-plugin
                """;
        List<EntryOptions> entries = boot.readProfileText(profile, "ghost.yml");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> boot.launch(entries))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-plugin");
    }

    @Test
    void customOpenAiCompatibleProviderReplacesTheMock() throws IOException {
        // an OpenAI-compatible stub speaks for the user's own endpoint; no key
        com.sun.net.httpserver.HttpServer stub = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        stub.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            boolean hasToolResult = body.contains("\"role\":\"tool\"");
            String response = hasToolResult
                    ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"calculated: 3\"}}]}"
                    : """
                            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                              {"id":"c1","type":"function","function":{"name":"calc","arguments":"{\\"expression\\":\\"1+2\\"}"}}
                            ]}}]}
                            """;
            byte[] payload = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        stub.start();
        try {
            String baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
            String profile = """
                    - id: session
                      name: session
                    - id: session-projections
                      name: session-projections
                    - id: tools
                      name: tools
                    - id: llm
                      name: llm
                      config:
                        defaultModel: local
                    - id: llm-openai
                      name: llm-openai
                      config:
                        name: local
                        model: my-own-model
                        baseUrl: %s
                    - id: agent-loop
                      name: agent-loop
                    - id: calc
                      name: calc
                    """.formatted(baseUrl);
            Context root = Context.create();
            HarnessBoot boot = new HarnessBoot(root)
                    .register(CalculatorToolPlugin.NAME, new CalculatorToolPlugin())
                    .register(RunnerPlugin.NAME, new RunnerPlugin());
            List<EntryOptions> entries = new ArrayList<>(boot.readProfileText(profile, "custom.yml"));
            entries.add(HarnessBoot.entry(RunnerPlugin.NAME, RunnerPlugin.NAME, Map.of("task", "1+2")));
            boot.launch(entries);

            assertThat(boot.loader().expectFiber(HarnessBoot.PLUGIN_LLM_OPENAI).state())
                    .isEqualTo(FiberState.ACTIVE);
            SessionService sessions = boot.service(SessionService.NAME);
            String sessionId = sessions.sessionIds().get(0);
            List<SessionEvent> events = sessions.events(sessionId);
            assertThat(events).extracting(SessionEvent::type).containsExactly(
                    SessionEventType.TURN_START,
                    SessionEventType.USER_MESSAGE,
                    SessionEventType.REQUEST_HEADER,
                    SessionEventType.ASSISTANT_MESSAGE,
                    SessionEventType.TOOL_RESULT,
                    SessionEventType.REQUEST_HEADER,
                    SessionEventType.ASSISTANT_MESSAGE,
                    SessionEventType.TURN_END);
            assertThat(events.get(6).content()).isEqualTo("calculated: 3");
            boot.dispose();
        } finally {
            stub.stop(0);
        }
    }
}
