package io.majo.harness.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxSeamTest {

    @Test
    void identityConfinesToItselfAndFactoryPicksProviders() {
        assertThat(new IdentitySandboxProvider().confine(List.of("echo", "x")))
                .containsExactly("echo", "x");
        assertThat(SandboxPlugin.providerOf(null).name()).isEqualTo("identity");
        assertThat(SandboxPlugin.providerOf(java.util.Map.of()).name()).isEqualTo("identity");
        assertThat(SandboxPlugin.providerOf(java.util.Map.of("provider", "identity")).name())
                .isEqualTo("identity");
        assertThatThrownBy(() -> SandboxPlugin.providerOf(java.util.Map.of("provider", "firejail")))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("firejail");
    }

    @Test
    void bwrapAssemblesConfinedArgvAndValidates() {
        BwrapSandboxProvider bwrap = new BwrapSandboxProvider(List.of("--unshare-all", "--ro-bind", "/", "/"));
        assertThat(bwrap.name()).isEqualTo("bwrap");
        assertThat(bwrap.confine(List.of("/bin/sh", "-c", "echo hi")))
                .containsExactly("bwrap", "--unshare-all", "--ro-bind", "/", "/", "--", "/bin/sh", "-c", "echo hi");

        assertThatThrownBy(() -> new BwrapSandboxProvider(List.of()))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("options");
        assertThatThrownBy(() -> bwrap.confine(List.of()))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("empty argv");
    }

    @Test
    void serviceConfinesThroughTheWaterfallAndPolicyCanReject() {
        Context root = Context.create();
        root.plugin(new SandboxPlugin(), null).await().join();
        SandboxService sandbox = root.get(SandboxService.NAME);
        assertThat(sandbox.provider().name()).isEqualTo("identity");
        assertThat(sandbox.confine(List.of("ls", "-la"))).containsExactly("ls", "-la");

        assertThatThrownBy(() -> sandbox.confine(List.of()))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("empty argv");

        root.on(SandboxEvents.PRE_CONFINE, (thisArg, args) -> {
            throw new SandboxException("denied by policy");
        });
        assertThatThrownBy(() -> sandbox.confine(List.of("ls")))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("denied by policy");
        root.fiber().disposeAsync().join();
    }
}
