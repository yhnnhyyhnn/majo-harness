#!/usr/bin/env bash
# Runs one module's tests INSIDE the reactor (-am), so sibling modules always
# resolve from this checkout — not from a stale local-repo SNAPSHOT.
#
# Why: `mvn -pl majo-web test` (no -am) silently uses whatever majo-* snapshot
# is installed in ~/.m2, which can lag this tree (missing methods/symbols
# that exist in the sources). Always build with -am or use this script.
#
# usage: bash scripts/test-module.sh <module> [extra -D flags...]
set -euo pipefail
cd "$(dirname "$0")/.."

MODULE="${1:-}"
[ -d "$MODULE" ] || { echo "usage: bash scripts/test-module.sh <module> [flags]" >&2; exit 2; }
shift || true

echo "==> mvn -pl $MODULE -am test (reactor build: sibling modules come from this tree)"
# shellcheck disable=SC2068
mvn -B -ntp -pl "$MODULE" -am test "$@"
echo "==> ok: $MODULE tests passed inside the reactor"
