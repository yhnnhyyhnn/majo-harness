#!/usr/bin/env bash
# Rebuilds the React/Vite chat UI and copies the compiled output into
# majo-web/src/main/resources/static so the Java jar serves it.
#
#   bash scripts/build-web-ui.sh
#
# Requirements: Node.js + npm. The built assets ARE committed, so a plain
# `mvn package` works without running this; run it again after UI edits.
set -euo pipefail
cd "$(dirname "$0")/.."
npm --prefix web-ui install
npm --prefix web-ui run build
rm -f majo-web/src/main/resources/static/index.html \
      majo-web/src/main/resources/static/app.css \
      majo-web/src/main/resources/static/app.js
cp -r web-ui/dist/. majo-web/src/main/resources/static/
rm -rf web-ui/node_modules web-ui/dist
echo "web UI copied to majo-web/src/main/resources/static"
