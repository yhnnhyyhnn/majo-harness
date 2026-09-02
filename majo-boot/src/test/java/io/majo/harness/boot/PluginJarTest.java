package io.majo.harness.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.tools.Tool;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the external-plugin-jar path end to end: a plugin is compiled at test
 * time (javax.tools) into a jar with an SPI manifest, loaded into an isolated
 * class loader, referenced by a profile row, then hot-replaced and unloaded —
 * all offline, with real class isolation (the plugin classes exist only in the
 * jar).
 */
class PluginJarTest {

    private static final String SERVICE = "META-INF/services/io.jcordis.core.registry.Plugin";
    private static final String CLASS_NAME = "external.demo.EchoToolPlugin";

    /** The plugin source registers a tool named after {@code version}. */
    private static String source(String toolName) {
        return """
                package external.demo;

                import io.jcordis.core.context.Context;
                import io.jcordis.core.registry.Plugin;
                import io.jcordis.core.util.Disposable;
                import io.majo.harness.tools.Tool;
                import io.majo.harness.tools.ToolCall;
                import io.majo.harness.tools.ToolResult;
                import io.majo.harness.tools.ToolRegistry;
                import io.majo.harness.tools.ToolSpec;
                import java.util.HashMap;
                import java.util.Map;

                public final class EchoToolPlugin implements Plugin {
                    @Override
                    public Object apply(Context ctx, Object config) {
                        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
                        Tool tool = new Tool() {
                            @Override
                            public ToolSpec spec() {
                                return ToolSpec.of("__TOOL__", "echoes from an external plugin jar");
                            }

                            @Override
                            public ToolResult execute(ToolCall call) {
                                return ToolResult.ok("echo:" + call.arguments());
                            }
                        };
                        Disposable registration = tools.register(tool);
                        return registration;
                    }

                    @Override
                    public Map<String, Object> inject() {
                        Map<String, Object> inject = new HashMap<>();
                        inject.put(ToolRegistry.NAME, null);
                        return inject;
                    }
                }
                """.replace("__TOOL__", toolName);
    }

    /** Compiles {@code source} and jars it with the SPI manifest. */
    private static Path jar(Path dir, String toolName) throws IOException {
        Path sourceDir = dir.resolve("src");
        Path classes = dir.resolve("classes");
        Files.createDirectories(sourceDir);
        Files.createDirectories(classes);
        Path sourceFile = sourceDir.resolve("EchoToolPlugin.java");
        Files.writeString(sourceFile, source(toolName), StandardCharsets.UTF_8);
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(), sourceFile.toString());
        if (compiled != 0) {
            throw new IllegalStateException("cannot compile test plugin (exit " + compiled + ")");
        }

        Path jarFile = dir.resolve(toolName + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarFile))) {
            out.putNextEntry(new JarEntry(SERVICE));
            out.write((CLASS_NAME + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            Path pkg = classes.resolve("external/demo");
            try (java.util.stream.Stream<Path> files = Files.list(pkg)) {
                for (Path classFile : files.filter(path -> path.getFileName().toString().endsWith(".class")).toList()) {
                    out.putNextEntry(new JarEntry("external/demo/" + classFile.getFileName()));
                    out.write(Files.readAllBytes(classFile));
                    out.closeEntry();
                }
            }
        }
        return jarFile;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor;
    }

    private static List<String> registeredTools(HarnessBoot boot) {
        ToolRegistry tools = boot.service(ToolRegistry.NAME);
        return tools.specs().stream().map(spec -> spec.name()).toList();
    }

    @Test
    void externalJarPluginBootsHotReplacesAndUnloads(@TempDir Path dir) throws IOException {
        Path v1 = jar(dir, "echo_v1");
        Path v2 = jar(dir, "echo_v2");
        String pluginName = "external-echo";

        Context root = Context.create();
        new io.jcordis.core.logger.ConsoleExporter(root);
        HarnessBoot boot = new HarnessBoot(root);
        Plugin loaded = boot.loadPluginJar(v1, pluginName);
        assertThat(loaded).isNotNull();

        String profile = """
                - id: tools
                  name: tools
                - id: ext
                  name: %s
                """.formatted(pluginName);
        boot.launch(boot.readProfileText(profile, "ext.yml"));

        // surface any fiber failure loudly (await() carries the body error)
        try {
            boot.loader().expectFiber("ext").await().join();
        } catch (Throwable failure) {
            throw new AssertionError("external plugin fiber failed", rootCause(failure));
        }
        assertThat(boot.loader().expectFiber("ext").state())
                .isEqualTo(io.jcordis.core.fiber.FiberState.ACTIVE);
        assertThat(registeredTools(boot)).containsExactly("echo_v1");

        ToolRegistry tools = boot.service(ToolRegistry.NAME);
        ToolResult result = tools.execute(ToolCall.of("echo_v1", "hi"));
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).isEqualTo("echo:hi");

        // hot replacement: the same row reloads against the new jar
        boot.loader().replaceJar(v2, pluginName);
        boot.loader().await();
        assertThat(registeredTools(boot)).containsExactly("echo_v2");
        ToolResult replaced = tools.execute(ToolCall.of("echo_v2", "again"));
        assertThat(replaced.ok()).isTrue();
        assertThat(replaced.content()).isEqualTo("echo:again");

        // unload disposes the row and closes the plugin classes
        boot.loader().unload(pluginName);
        boot.loader().await();
        assertThat(registeredTools(boot)).isEmpty();
        assertThat(boot.loader().expectFiber("ext")).isNull();
        boot.dispose();
    }

    @Test
    void jarWithoutSpiManifestFailsLoud(@TempDir Path dir) throws IOException {
        Path empty = dir.resolve("empty.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(empty))) {
            out.putNextEntry(new JarEntry("nothing/here.txt"));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Context root = Context.create();
        HarnessBoot boot = new HarnessBoot(root);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> boot.loadPluginJar(empty, "broken"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no Plugin implementation");
        boot.dispose();
    }
}
