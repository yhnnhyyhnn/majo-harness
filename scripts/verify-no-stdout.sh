#!/usr/bin/env bash
# Gate: service code must log via slf4j, not print to stdout/stderr.
#
# Rationale (dsh-style repo invariant): stray println hides behind no log
# level and cannot be filtered by operators. Product-output channels are
# exempt — the CLI/transcript printers write to explicit streams by design,
# and WebTypesGenerator's stdout IS its generated-file report.
set -euo pipefail
cd "$(dirname "$0")/.."

violations=$(grep -rn --include='*.java' -E 'System\.out\.|System\.err\.|\.printStackTrace\(\)' \
  majo-session/src/main majo-util/src/main majo-tools/src/main majo-llm/src/main \
  majo-agent-loop/src/main majo-provider-openai/src/main majo-fs/src/main \
  majo-sandbox/src/main majo-interaction/src/main majo-skill/src/main \
  majo-subagent/src/main majo-settings/src/main majo-credentials/src/main \
  majo-title/src/main majo-web/src/main majo-shell/src/main majo-subprocess/src/main \
  majo-web-access/src/main majo-boot/src/main majo-cli/src/main majo-headless/src/main \
  2>/dev/null \
  | grep -v -E 'WebTypesGenerator\.java|/TranscriptPrinter\.java' || true)

if [ -n "$violations" ]; then
  echo "verify-no-stdout: println/stack-trace usage in service code:"
  echo "$violations"
  exit 1
fi
echo "verify-no-stdout: OK"
