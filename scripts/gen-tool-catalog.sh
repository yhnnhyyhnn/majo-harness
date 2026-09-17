#!/usr/bin/env bash
# Regenerates docs/tool-catalog.md from the shipped offline profile's live
# ToolRegistry (gen + verify pairing: ToolCatalogTest asserts freshness in
# the build).
#
#   bash scripts/gen-tool-catalog.sh
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -pl majo-web -am test -Dtest=ToolCatalogTest \
  -Dmajo.gen.tool-catalog=true -Dsurefire.failIfNoSpecifiedTests=false
echo "docs/tool-catalog.md regenerated (review + commit)"
