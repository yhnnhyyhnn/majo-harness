package io.majo.harness.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.util.Disposable;
import io.majo.harness.boot.commands.CommandRegistry;
import io.majo.harness.boot.commands.CommandRegistryPlugin;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandRegistryTest {

    @Test
    void registerListAndRunCommands() {
        Context ctx = Context.create();
        ctx.plugin(new CommandRegistryPlugin(), null).await().join();
        CommandRegistry commands = ctx.get(CommandRegistry.NAME);
        Disposable first = commands.register("echo", "returns its arg",
                (context, args) -> String.valueOf(args.getOrDefault("text", "")));
        Disposable duplicate = commands.register("upper", "upper-cases",
                (context, args) -> String.valueOf(args.getOrDefault("text", "")).toUpperCase());

        assertThat(commands.run("echo", Map.of("text", "hello"))).isEqualTo("hello");
        assertThat(commands.run("upper", Map.of("text", "hey"))).isEqualTo("HEY");
        assertThat(commands.entries()).extracting(CommandRegistry.Entry::name)
                .containsExactly("echo", "upper");

        // unknown + duplicates fail loudly; disposers roll registrations back
        assertThatThrownBy(() -> commands.run("nope", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown command");
        assertThatThrownBy(() -> commands.register("echo", "dup", (a, b) -> ""))
                .isInstanceOf(IllegalStateException.class);
        first.dispose();
        assertThatThrownBy(() -> commands.run("echo", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        duplicate.dispose();
        assertThat(commands.entries()).isEmpty();
        ctx.fiber().disposeAsync().join();
    }
}
