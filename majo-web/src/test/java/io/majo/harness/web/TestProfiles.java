package io.majo.harness.web;

import java.nio.file.Path;

/**
 * Shared profile builder for web integration tests (soak + openapi drift).
 * Options toggle the rows that the two suites differ on; every profile stays
 * fully offline with the deterministic mock model.
 */
final class TestProfiles {

    /** Variation switches between the web test profiles. */
    record Options(boolean gateCalc, boolean titleRows, String agentLoopConfig,
            java.util.List<String> pluginRows) {
        static Options minimal() {
            return new Options(false, false, null, java.util.List.of());
        }

        Options withTitleRows() {
            return new Options(gateCalc, true, agentLoopConfig, pluginRows);
        }

        Options gate(boolean calcGated) {
            return new Options(calcGated, titleRows, agentLoopConfig, pluginRows);
        }

        Options withPluginRow(String pluginRow) {
            java.util.List<String> rows = new java.util.ArrayList<>(pluginRows);
            rows.add(pluginRow);
            return new Options(gateCalc, titleRows, agentLoopConfig, rows);
        }

        Options withAgentLoopConfig(String configYaml) {
            return new Options(gateCalc, titleRows, configYaml, pluginRows);
        }
    }

    private TestProfiles() {
    }

    /** Builds the shared offline mock profile as YAML text. */
    static String yml(Path sessionsDir, Path settingsFile, Options options) {
        StringBuilder yml = new StringBuilder();
        yml.append("""
                - id: session
                  name: session
                  config:
                    store: file
                    path: %s
                - id: session-projections
                  name: session-projections
                - id: tools
                  name: tools
                - id: settings
                  name: settings
                  config:
                    path: %s
                - id: llm
                  name: llm
                  config:
                    defaultModel: mock
                - id: llm-mock
                  name: llm-mock
                - id: credentials
                  name: credentials
                """.formatted(sessionsDir.toString().replace('\\', '/'),
                settingsFile.toString().replace('\\', '/')));
        if (options.titleRows()) {
            yml.append("""
                    - id: session-title
                      name: session-title
                    - id: session-title-heuristic
                      name: session-title-heuristic
                    """);
        }
        yml.append("""
                - id: agent-loop
                  name: agent-loop
                """);
        if (options.agentLoopConfig() != null) {
            yml.append("  config:\n").append(options.agentLoopConfig());
        }
        if (options.gateCalc()) {
            yml.append("""
                    - id: interactions
                      name: interactions
                    - id: tool-approval
                      name: tool-approval
                      config:
                        tools: [calc]
                    """);
        }
        for (String row : options.pluginRows()) {
            yml.append(row);
        }
        yml.append("""
                - id: calc
                  name: calc
                """);
        return yml.toString();
    }
}
