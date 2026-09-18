package io.majo.harness.ssh;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One SSH execution target (dsh `ssh` family analog): host, user, optional
 * port/identity file. Values come from profile rows; secrets stay outside
 * the profile (key paths reference files, passwords use the agent).
 *
 * <p>The target formats the OpenSSH argv prefix — every remote operation
 * shares the same connection parameters ("single execution world").
 */
public record SshTarget(String host, int port, String user, String identityFile) {

    public static final int DEFAULT_PORT = 22;

    /** Parses from a profile config map: {@code {host, user, port?, identityFile?}}. */
    public static SshTarget fromConfig(Map<?, ?> map) {
        String host = map.get("host") == null ? null : String.valueOf(map.get("host"));
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("ssh: host is required");
        }
        int port = map.get("port") instanceof Number number && number.intValue() > 0
                ? number.intValue() : DEFAULT_PORT;
        String user = map.get("user") == null ? null : String.valueOf(map.get("user"));
        String identity = map.get("identityFile") == null
                ? null : String.valueOf(map.get("identityFile"));
        return new SshTarget(host, port, user, identity);
    }

    /** The {@code [user@]host[:port]} label for diagnostics. */
    public String label() {
        String base = user == null ? host : user + "@" + host;
        return port == DEFAULT_PORT ? base : base + ":" + port;
    }

    /** The OpenSSH argv prefix (before the remote command). */
    public List<String> argvPrefix() {
        List<String> argv = new ArrayList<>();
        argv.add("ssh");
        argv.add("-o"); argv.add("BatchMode=yes");
        argv.add("-o"); argv.add("StrictHostKeyChecking=accept-new");
        argv.add("-o"); argv.add("ConnectTimeout=10");
        if (port != DEFAULT_PORT) {
            argv.add("-p"); argv.add(String.valueOf(port));
        }
        if (identityFile != null) {
            argv.add("-i"); argv.add(identityFile);
        }
        if (user != null) {
            argv.add(user + "@" + host);
        } else {
            argv.add(host);
        }
        return List.copyOf(argv);
    }
}
