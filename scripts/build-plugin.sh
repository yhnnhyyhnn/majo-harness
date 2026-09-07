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

JCORDIS_JAR="${JCORDIS_JAR:-$HOME/.m2/repository/io/jcordis/jcordis-all/1.0.1-SNAPSHOT/jcordis-all-1.0.1-SNAPSHOT.jar}"
if [ ! -f "$JCORDIS_JAR" ]; then
  JCORDIS_JAR="/d/mvn_repository/io/jcordis/jcordis-all/1.0.1-SNAPSHOT/jcordis-all-1.0.1-SNAPSHOT.jar"
fi
if [ ! -f "$JCORDIS_JAR" ]; then
  JCORDIS_JAR="${JCORDIS_JAR:-lib/jcordis-all-1.0.1-SNAPSHOT.jar}"
fi
if [ ! -f "$JCORDIS_JAR" ]; then
  echo "jcordis-all jar not found; set JCORDIS_JAR" >&2
  exit 1
fi

OUT="$SRC/target/classes"
rm -rf "$SRC/target"
mkdir -p "$OUT/META-INF/services"
find "$SRC/src/main/java" -name '*.java' -print0 | xargs -0 javac -cp "$JCORDIS_JAR" -d "$OUT"
cp -r "$SRC/src/main/resources/static-web" "$OUT/"
find "$SRC/src/main/java" -name '*Plugin.java' -exec basename {} .java \; \
  | sed 's/^/io.majo.example.template./' \
  | head -1 > /dev/null
PKG=$(find "$SRC/src/main/java" -name '*Plugin.java' | head -1 | sed "s#$SRC/src/main/java/##; s#/#.#g; s#.java\$##")
printf '%s\n' "$PKG" > "$OUT/META-INF/services/io.jcordis.core.registry.Plugin"
jar cf "$SRC/${NAME}.jar" -C "$OUT" .
echo "built $SRC/${NAME}.jar"
