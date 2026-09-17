#!/usr/bin/env bash
# Release pipeline: full gates -> CHANGELOG finalization -> lockstep version
# stamp (every pom + web-ui) -> full verify at the release version -> jar
# version assertion -> release commit + tag -> push attempt (direct, then the
# local Clash proxy).
#
#   bash scripts/release.sh <version>      # e.g. 0.2.0 (no v prefix)
#
# Requirements: clean working tree; CHANGELOG carries an [Unreleased] section.
set -euo pipefail
cd "$(dirname "$0")/.."

version=${1:-}
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo "usage: release.sh <major.minor.patch> (e.g. 0.2.0)"; exit 2;
}
[[ -z $(git status --porcelain) ]] || {
  echo "working tree not clean — commit or stash first"; exit 2;
}
current=$(sed -n 's|.*<version>\(.*-SNAPSHOT\)</version>.*|\1|p' pom.xml | head -1)
[[ -n "$current" ]] || { echo "cannot detect the current -SNAPSHOT version from pom.xml"; exit 2; }
today=$(date +%Y-%m-%d)

echo "== release $current -> $version =="

echo "== step 1/5: finalize CHANGELOG ([Unreleased] -> [$version]) =="
grep -q '^## \[Unreleased\]' CHANGELOG.md || {
  echo "CHANGELOG has no [Unreleased] section — nothing to release"; exit 2;
}
sed -i "s|^## \[Unreleased\]|## [$version] - $today|" CHANGELOG.md
# retire the trailing working-area line under the release heading and reopen
# a fresh [Unreleased] at the end
awk 'BEGIN{blank=""} {lines[NR]=$0} END{
  n=NR; while (n>0 && (lines[n]=="" || lines[n]=="Working area for the next iteration.")) n--;
  for (i=1;i<=n;i++) print lines[i];
  print ""; print "## [Unreleased]"; print ""; print "Working area for the next iteration.";
}' CHANGELOG.md > CHANGELOG.md.tmp && mv CHANGELOG.md.tmp CHANGELOG.md

echo "== step 2/5: lockstep version stamp =="
# Git Bash grep emits backslash paths with CRLF line endings: normalize both
# (tr eats the \r and flips the slashes) so the target filter and sed behave
grep -rl "$current" --include="pom.xml" . 2>/dev/null | tr -d '\r' | tr '\\' '/' \
  | grep -v "/target/" | while read -r f; do
  sed -i "s/$current/$version/g" "$f"
done
# npm version updates package.json AND package-lock.json atomically (npm ci
# fails the build when the two drift apart); "Version not changed" is fine on
# an idempotent re-run
(cd web-ui && npm version "$version" --no-git-tag-version > /dev/null) \
  || echo "npm version: already at $version"
# example javadoc references the web jar by name
grep -rl "majo-web-$current.jar" --include="*.java" . 2>/dev/null | tr -d '\r' | tr '\\' '/' \
  | while read -r f; do
  sed -i "s/majo-web-$current\.jar/majo-web-$version.jar/g" "$f"
done
remaining=$(grep -rl "$current" --include="pom.xml" . 2>/dev/null | grep -cv "/target/" || true)
[[ "$remaining" == "0" ]] || { echo "stamp incomplete: $remaining pom(s) still carry $current"; exit 1; }

echo "== step 3/5: full gates at the release version =="
bash scripts/check.sh

echo "== step 4/5: jar version assertion =="
mvn -q -pl majo-web package -DskipTests -Dskip.webui=true
jar_path=$(ls majo-web/target/majo-web-*.jar | head -1)
tmp=$(mktemp -d)
if command -v unzip >/dev/null 2>&1; then
  (cd "$tmp" && unzip -q -o "$OLDPWD/$jar_path" majo/version.properties)
elif [ -x "$(dirname "$(which java)")/jar" ]; then
  (cd "$tmp" && "$(dirname "$(which java)")/jar" -xf "$OLDPWD/$jar_path" majo/version.properties)
else
  echo "WARN: neither unzip nor jar available — skipping the jar version assertion"
fi
if [ -f "$tmp/majo/version.properties" ]; then
  grep -q "version=$version" "$tmp/majo/version.properties" || {
    echo "jar version assertion failed: expected $version, got:"; cat "$tmp/majo/version.properties"; exit 1;
  }
  echo "jar carries version=$version"
fi
rm -rf "$tmp"

echo "== step 5/5: release commit + tag + push attempt =="
git add -A
git commit -m "release: v$version"
git tag "v$version"
pushed=0
if git push origin main "v$version" 2>/dev/null; then
  pushed=1
elif git -c http.proxy=http://127.0.0.1:7890 push origin main "v$version" 2>/dev/null; then
  pushed=1
fi
if [[ "$pushed" == "1" ]]; then
  echo "pushed main + v$version"
else
  echo "WARN: push failed — run 'git push origin main v$version' when the network allows"
fi

echo "release v$version complete"
