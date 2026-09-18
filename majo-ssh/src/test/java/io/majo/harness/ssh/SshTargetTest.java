package io.majo.harness.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SshTargetTest {

    @Test
    void parsesFromConfig() {
        SshTarget target = SshTarget.fromConfig(Map.of("host", "myhost", "user", "me"));
        assertThat(target.host()).isEqualTo("myhost");
        assertThat(target.user()).isEqualTo("me");
        assertThat(target.port()).isEqualTo(22);
        assertThat(target.identityFile()).isNull();
        assertThat(target.label()).isEqualTo("me@myhost");
    }

    @Test
    void fullConfigParsesPortAndIdentity() {
        SshTarget target = SshTarget.fromConfig(Map.of(
                "host", "10.0.0.1", "port", 2222, "user", "deploy",
                "identityFile", "/home/me/.ssh/id_ed25519"));
        assertThat(target.port()).isEqualTo(2222);
        assertThat(target.identityFile()).isEqualTo("/home/me/.ssh/id_ed25519");
        assertThat(target.label()).isEqualTo("deploy@10.0.0.1:2222");
    }

    @Test
    void hostIsRequired() {
        assertThatThrownBy(() -> SshTarget.fromConfig(Map.of()))
                .hasMessageContaining("host is required");
        assertThatThrownBy(() -> SshTarget.fromConfig(Map.of("host", "")))
                .hasMessageContaining("host is required");
    }

    @Test
    void argvPrefixContainsBatchModeAndHost() {
        SshTarget target = SshTarget.fromConfig(Map.of("host", "myhost", "user", "me"));
        var argv = target.argvPrefix();
        assertThat(argv).first().isEqualTo("ssh");
        assertThat(argv).contains("-o", "BatchMode=yes");
        assertThat(argv).contains("me@myhost");
        assertThat(argv).doesNotContain("-p", "2222", "-i");
    }

    @Test
    void argvPrefixIncludesPortAndIdentity() {
        SshTarget target = SshTarget.fromConfig(Map.of(
                "host", "h", "port", 2222, "identityFile", "/key"));
        var argv = target.argvPrefix();
        assertThat(argv).contains("-p", "2222", "-i", "/key");
    }

    @Test
    void shQuoteWrapsSingleQuotes() {
        assertThat(SshExec.shQuote("simple")).isEqualTo("'simple'");
        assertThat(SshExec.shQuote("it's")).isEqualTo("'it'\\''s'");
        assertThat(SshExec.shQuote("a b$c")).isEqualTo("'a b$c'");
    }
}
