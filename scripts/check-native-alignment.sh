#!/usr/bin/env bash
# Fails unless every native library in the given APKs works on 16 KB page devices (decision D7):
# each ELF LOAD segment aligned to at least 16 KB, and each uncompressed library zip-aligned to
# 16 KB inside the APK.
#
# Usage: scripts/check-native-alignment.sh <apk>...
# Needs ANDROID_HOME with build-tools (zipalign) and the NDK version pinned in
# gradle/libs.versions.toml (llvm-readelf).
set -euo pipefail

if [[ $# -eq 0 ]]; then
    echo "usage: $0 <apk>..." >&2
    exit 2
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ndk_version="$(sed -n 's/^ndk = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")"
readelf="$ANDROID_HOME/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
zipalign="$(find "$ANDROID_HOME/build-tools" -name zipalign -type f | sort -V | tail -n 1)"
[[ -x "$readelf" ]] || { echo "llvm-readelf not found for NDK $ndk_version" >&2; exit 2; }
[[ -x "$zipalign" ]] || { echo "zipalign not found in $ANDROID_HOME/build-tools" >&2; exit 2; }

min_alignment=$((16 * 1024))
failed=0
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

for apk in "$@"; do
    echo "== $apk"
    if ! "$zipalign" -c -P 16 4 "$apk"; then
        echo "FAIL: libraries are not zip-aligned to 16 KB" >&2
        failed=1
    fi

    unzip -q -o "$apk" 'lib/*' -d "$scratch/$(basename "$apk")" 2>/dev/null || true
    libraries=$(find "$scratch/$(basename "$apk")" -name '*.so' 2>/dev/null || true)
    if [[ -z "$libraries" ]]; then
        echo "FAIL: no native libraries found" >&2
        failed=1
        continue
    fi
    while IFS= read -r library; do
        name="${library#"$scratch/"}"
        segments=$("$readelf" -lW "$library" | awk '$1 == "LOAD" { print $NF }')
        if [[ -z "$segments" ]]; then
            echo "FAIL: $name has no LOAD segments" >&2
            failed=1
            continue
        fi
        library_ok=1
        while IFS= read -r alignment; do
            if (( $(printf '%d' "$alignment") < min_alignment )); then
                echo "FAIL: $name has a LOAD segment aligned to $alignment" >&2
                library_ok=0
                failed=1
            fi
        done <<< "$segments"
        (( library_ok )) && echo "ok: $name"
    done <<< "$libraries"
done

exit "$failed"
