#!/usr/bin/env bash
# Rebuilds the React/Vite chat UI through Maven and copies the compiled output
# into majo-web/src/main/resources/static (majo-web-ui module, generate-resources).
#
#   bash scripts/build-web-ui.sh           # npm ci + vite build + copy
#   mvn -pl web-ui generate-resources      # equivalent Maven-only invocation
#   mvn clean verify -Dskip.webui=true     # skip (uses the committed copy)
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -pl web-ui generate-resources
echo "web UI built and copied to majo-web/src/main/resources/static"
