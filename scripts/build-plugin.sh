#!/usr/bin/env bash
# Builds any scaffolded plugin under examples/<name>-plugin (see
# scripts/new-plugin.sh) into examples/<name>-plugin/<name>.jar.
set -euo pipefail
cd "$(dirname "$0")/.."

NAME="${1:-}"
if [ -z "$NAME" ] || ! [[ "$NAME" =~ ^[a-z][a-z0-9_-]*$ ]]; then
  echo "usage: bash scripts/build-plugin.sh <name>" >&2
  exit 2
fi
SRC="examples/${NAME}-plugin"
[ -d "$SRC/src/main/java" ] || { echo "error: $SRC not found (scaffold it first)" >&2; exit 1; }

JCORDIS_CORE="${JCORDIS_CORE:-$HOME/.m2/repository/io/github/yhnnhyyhnn/jcordis-core/1.0.1/jcordis-core-1.0.1.jar}"
if [ ! -f "$JCORDIS_CORE" ]; then
  JCORDIS_CORE="/d/mvn_repository/io/github/yhnnhyyhnn/jcordis-core/1.0.1/jcordis-core-1.0.1.jar"
fi
if [ ! -f "$JCORDIS_CORE" ]; then
  echo "jcordis-core jar not found; set JCORDIS_CORE (io.github.yhnnhyyhnn:jcordis-core:1.0.1 from Central)" >&2
  exit 1
fi
JCORDIS_LOADER="${JCORDIS_LOADER:-$HOME/.m2/repository/io/github/yhnnhyyhnn/jcordis-loader/1.0.1/jcordis-loader-1.0.1.jar}"
if [ ! -f "$JCORDIS_LOADER" ]; then
  JCORDIS_LOADER="/d/mvn_repository/io/github/yhnnhyyhnn/jcordis-loader/1.0.1/jcordis-loader-1.0.1.jar"
fi
if [ ! -f "$JCORDIS_LOADER" ]; then
  echo "jcordis-loader jar not found; set JCORDIS_LOADER (io.github.yhnnhyyhnn:jcordis-loader:1.0.1 from Central)" >&2
  exit 1
fi

OUT="$SRC/target/classes"
rm -rf "$SRC/target"
mkdir -p "$OUT/META-INF/services"
find "$SRC/src/main/java" -name '*.java' -print0 | xargs -0 javac -cp "$JCORDIS_CORE:$JCORDIS_LOADER" -d "$OUT"
cp -r "$SRC/src/main/resources/static-web" "$OUT/"
find "$SRC/src/main/java" -name '*Plugin.java' -exec basename {} .java \; \
  | sed 's/^/io.majo.example.template./' \
  | head -1 > /dev/null
PKG=$(find "$SRC/src/main/java" -name '*Plugin.java' | head -1 | sed "s#$SRC/src/main/java/##; s#/#.#g; s#.java\$##")
printf '%s\n' "$PKG" > "$OUT/META-INF/services/io.jcordis.core.registry.Plugin"
jar cf "$SRC/${NAME}.jar" -C "$OUT" .
echo "built $SRC/${NAME}.jar"
