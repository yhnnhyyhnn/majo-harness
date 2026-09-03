#!/usr/bin/env bash
# Builds the web-plugin demo jar (backend SPI + static frontend) without a
# Maven module: javac against jcordis-all from the local repo, then packs the
# static-web assets and the SPI services file into the jar.
set -euo pipefail
cd "$(dirname "$0")/.."

JCORDIS_JAR="${JCORDIS_JAR:-$HOME/.m2/repository/io/jcordis/jcordis-all/1.0.0/jcordis-all-1.0.0.jar}"
if [ ! -f "$JCORDIS_JAR" ]; then
  JCORDIS_JAR="/d/mvn_repository/io/jcordis/jcordis-all/1.0.0/jcordis-all-1.0.0.jar"
fi
if [ ! -f "$JCORDIS_JAR" ]; then
  echo "jcordis-all jar not found; set JCORDIS_JAR" >&2
  exit 1
fi

SRC=examples/web-plugin-demo
OUT="$SRC/target/classes"
rm -rf "$SRC/target"
mkdir -p "$OUT/META-INF/services"

javac -cp "$JCORDIS_JAR" -d "$OUT" "$SRC/src/main/java/io/majo/example/plugin/WebDemoPlugin.java"
cp -r "$SRC/src/main/resources/static-web" "$OUT/"
printf '%s\n' io.majo.example.plugin.WebDemoPlugin \
  > "$OUT/META-INF/services/io.jcordis.core.registry.Plugin"

jar cf "$SRC/web-demo.jar" -C "$OUT" .
echo "built $SRC/web-demo.jar"
