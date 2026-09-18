# majo-harness v0.6.2 release notes (2026-09-18)

Tenth tagged release — adds the **SSH remote-execution family**, enabling
the harness to point its `fs` and `subprocess` seams at a remote host.
Everything below keeps `scripts/check.sh` green (no-stdout gate, ESLint,
vitest, full Maven verify across 33 modules). Full item history:
`CHANGELOG.md`.

## Highlights

- **SSH remote-execution family**: the `fs` and `subprocess` plugins gain
  an optional `ssh: {host, user, port?, identityFile?}` config — when
  present, file operations (read/write/glob) and command execution run on
  the remote host via the OpenSSH CLI instead of locally. The invariant
  "local path access is never inferred from a remote path string" holds:
  file reads and commands see the same remote world. Connection reuse is
  delegated to the user's `~/.ssh/config` (ControlMaster/ControlPersist).

## Upgrading from v0.6.1

- No breaking changes. To point the harness at a remote host:

  ```yaml
  - id: fs
    name: fs
    config:
      ssh:
        host: myserver.example.com
        user: deploy
  - id: subprocess
    name: subprocess
    config:
      ssh:
        host: myserver.example.com
        user: deploy
  ```

  Requires passwordless SSH (key auth via agent or `identityFile`);
  POSIX remote hosts only.
