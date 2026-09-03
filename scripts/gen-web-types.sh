#!/usr/bin/env bash
# Regenerates web-ui/src/types.ts from the Java wire contract
# (WebApiModels DTOs + SessionEventType) and rebuilds the UI.
#
#   bash scripts/gen-web-types.sh
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -pl majo-web -am compile
mvn -q -pl majo-web exec:java \
  -Dexec.mainClass=io.majo.harness.web.WebTypesGenerator \
  -Dexec.args="web-ui/src/types.ts"
echo "web-ui/src/types.ts regenerated (review + commit, then rebuild the UI)"
