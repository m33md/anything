#!/usr/bin/env bash
# Builds the app and lays out the Windows folder (same layout as Olympus Reader):
#   dist/KolNovelReader/app/*.jar  + launchers + README.txt
# The runtime/ (Java) folder is copied by the launcher from ..\OlympusReader\runtime on first start.
set -euo pipefail
cd "$(dirname "$0")/.."
gradle --no-daemon -q jar
out=dist/KolNovelReader
rm -rf "$out" && mkdir -p "$out/app"
cp build/libs/KolNovelReader-*.jar "$out/app/"
for j in libs/*.jar; do
  case "$(basename "$j")" in coil-*) ;; *) cp "$j" "$out/app/" ;; esac
done
cp packaging/*.bat packaging/README.txt "$out/"
(cd dist && rm -f KolNovelReader.zip && zip -qr KolNovelReader.zip KolNovelReader)
echo "built $out and dist/KolNovelReader.zip"
