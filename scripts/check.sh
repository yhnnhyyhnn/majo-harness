#!/usr/bin/env bash
# Single gate entry: the same checks run locally and in CI (dsh run-gates
# analog). Fast invariants first, full Maven verify last.
#
#   bash scripts/check.sh
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== gate: no-stdout (service code logs via slf4j) =="
bash scripts/verify-no-stdout.sh

echo "== gate: frontend lint =="
(cd web-ui && npm run lint)

echo "== gate: frontend unit tests (vitest) =="
(cd web-ui && npm run test)

echo "== gate: maven clean verify (all modules + web-ui build) =="
mvn -q clean verify

echo "check: all gates passed"
