#!/usr/bin/env bash
# Builds the app and lays out the Windows folder (same layout as Olympus Reader):
#   dist/KolNovelReader/app/*.jar  + launchers + README.txt
#   + runtime/ : the Temurin 21 Windows JRE (checksum-verified), so the folder runs on its own.
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf build/libs
gradle --no-daemon -q jar
out=dist/KolNovelReader
rm -rf "$out" && mkdir -p "$out/app"
cp build/libs/KolNovelReader-*.jar "$out/app/"
for j in libs/*.jar; do
  case "$(basename "$j")" in coil-*) ;; *) cp "$j" "$out/app/" ;; esac
done
cp packaging/*.bat packaging/README.txt "$out/"
jre_url="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8%2B9/OpenJDK21U-jre_x64_windows_hotspot_21.0.8_9.zip"
jre_sha=238d74ec4ec9422d416fa98805ba375eecd8bc8f971bd0c61a21051a4fe42db8
mkdir -p build/jre
[ -f build/jre/jre.zip ] || curl -sSL -o build/jre/jre.zip "$jre_url"
echo "$jre_sha  build/jre/jre.zip" | sha256sum -c -
rm -rf build/jre/x && unzip -q build/jre/jre.zip -d build/jre/x
mv build/jre/x/jdk-*-jre "$out/runtime"
(cd dist && rm -f KolNovelReader.zip && zip -qr KolNovelReader.zip KolNovelReader)
echo "built $out and dist/KolNovelReader.zip"
