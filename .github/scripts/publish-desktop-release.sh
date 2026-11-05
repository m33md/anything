#!/usr/bin/env bash
# Publishes the given files as the rolling "desktop-latest" pre-release, built from commit $1 as
# version $2. The installers keep the names the first download links used, whatever the version,
# and the notes carry the version and commit for the app's updater. The setup .exe is also there
# under a name with its version, for people who want to see which one they're getting.
#
# The tag points at a one-file commit rather than at the build commit: a GITHUB_TOKEN may not
# create a ref whose tree adds workflow files, and every commit on convx-desktop carries them.
set -euo pipefail
sha=$1
version=$2
shift 2
tag=desktop-latest

files=()
stable=$(mktemp -d)
for file in "$@"; do
  case "$file" in
    *.msi) cp "$file" "$stable/Convx-1.0.0.msi"; files+=("$stable/Convx-1.0.0.msi") ;;
    *.exe)
      cp "$file" "$stable/Convx-1.0.0.exe"; files+=("$stable/Convx-1.0.0.exe")
      cp "$file" "$stable/Convx-Setup-$version.exe"; files+=("$stable/Convx-Setup-$version.exe") ;;
    *) files+=("$file") ;;
  esac
done

gh release delete "$tag" --cleanup-tag --yes || true

tmp=$(mktemp -d)
git -C "$tmp" init -q -b release
printf 'Windows build of Convx, from commit %s of the convx-desktop branch.\nSource: https://github.com/%s/tree/%s\n' \
  "$sha" "$GITHUB_REPOSITORY" "$sha" > "$tmp/README.md"
git -C "$tmp" add README.md
git -C "$tmp" -c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
  commit -qm "Convx for Windows, built from ${sha::7}"
git -C "$tmp" push -qf "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}" "release:refs/tags/$tag"

gh release create "$tag" --verify-tag --prerelease \
  --title "Convx for Windows" \
  --notes "Download Convx-Setup-${version}.exe below and double-click it. If Windows says it protected your PC, click More info, then Run anyway. Version ${version}. Source: https://github.com/${GITHUB_REPOSITORY}/tree/${sha}" \
  "${files[@]}"
