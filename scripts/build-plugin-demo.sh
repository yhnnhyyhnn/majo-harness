#!/usr/bin/env bash
# Builds the web-plugin demo jar (backend SPI + static frontend) without a
# Maven module: javac against jcordis core/loader from Maven Central (local
# repo copy), then packs the static-web assets and the SPI services file.
set -euo pipefail
cd "$(dirname "$0")/.."

JCORDIS_CORE="${JCORDIS_CORE:-$HOME/.m2/repository/io/github/yhnnhyyhnn/jcordis-core/1.0.1/jcordis-core-1.0.1.jar}"
if [ ! -f "$JCORDIS_CORE" ]; then
  JCORDIS_CORE="/d/mvn_repository/io/github/yhnnhyyhnn/jcordis-core/1.0.1/jcordis-core-1.0.1.jar"
fi
if [ ! -f "$JCORDIS_CORE" ]; then
  echo "jcordis-core jar not found; set JCORDIS_CORE (io.github.yhnnhyyhnn:jcordis-core:1.0.1)" >&2
  exit 1
fi
JCORDIS_LOADER="${JCORDIS_LOADER:-$HOME/.m2/repository/io/github/yhnnhyyhnn/jcordis-loader/1.0.1/jcordis-loader-1.0.1.jar}"
if [ ! -f "$JCORDIS_LOADER" ]; then
  JCORDIS_LOADER="/d/mvn_repository/io/github/yhnnhyyhnn/jcordis-loader/1.0.1/jcordis-loader-1.0.1.jar"
fi
if [ ! -f "$JCORDIS_LOADER" ]; then
  echo "jcordis-loader jar not found; set JCORDIS_LOADER (io.github.yhnnhyyhnn:jcordis-loader:1.0.1)" >&2
  exit 1
fi

SRC=examples/web-plugin-demo
OUT="$SRC/target/classes"
rm -rf "$SRC/target"
mkdir -p "$OUT/META-INF/services"

javac -cp "$JCORDIS_CORE:$JCORDIS_LOADER" -d "$OUT" "$SRC/src/main/java/io/majo/example/plugin/WebDemoPlugin.java"
cp -r "$SRC/src/main/resources/static-web" "$OUT/"
printf '%s\n' io.majo.example.plugin.WebDemoPlugin \
  > "$OUT/META-INF/services/io.jcordis.core.registry.Plugin"

jar cf "$SRC/web-demo.jar" -C "$OUT" .
echo "built $SRC/web-demo.jar"
