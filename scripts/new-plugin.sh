#!/usr/bin/env bash
# Scaffolds a new web plugin under examples/<name>-plugin from the template in
# scripts/plugin-template. Usage: bash scripts/new-plugin.sh <name>
# The generated project builds with bash scripts/build-plugin.sh <name> (same
# javac + jar recipe as the shipped web-plugin-demo).
set -euo pipefail
cd "$(dirname "$0")/.."

NAME="${1:-}"
if [ -z "$NAME" ] || ! [[ "$NAME" =~ ^[a-z][a-z0-9_-]*$ ]]; then
  echo "usage: bash scripts/new-plugin.sh <lowercase-name>" >&2
  exit 2
fi
DEST="examples/${NAME}-plugin"
if [ -e "$DEST" ]; then
  echo "error: $DEST already exists" >&2
  exit 1
fi

cp -r scripts/plugin-template "$DEST"
find "$DEST" -type f | while read -r file; do
  sed -i "s/__name__/${NAME}/g" "$file"
done
# template paths contain the placeholder directory name
if [ -d "$DEST/src/main/resources/static-web/__name__" ]; then
  mv "$DEST/src/main/resources/static-web/__name__" "$DEST/src/main/resources/static-web/$NAME"
fi
echo "scaffolded $DEST"
echo "build:  bash scripts/build-plugin.sh $NAME"
echo "mount:  java -jar majo-web-0.1.0-SNAPSHOT.jar --profile web-mock --plugin ${NAME}=./${DEST}/${NAME}.jar"
