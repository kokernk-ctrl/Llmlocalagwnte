#!/usr/bin/env bash
set -euo pipefail
apk=${1:?Usage: verify_apk.sh APK [ANDROID_SDK]}
sdk=${2:-${ANDROID_HOME:?Pass SDK path or set ANDROID_HOME}}
tools=$sdk/build-tools/35.0.0
badging=$($tools/aapt2 dump badging "$apk")
grep -E '^(package:|minSdkVersion:|targetSdkVersion:|launchable-activity:)' <<< "$badging"
grep -q "^package: name='dev.forgeai' " <<< "$badging"
grep -qx "minSdkVersion:'26'" <<< "$badging"
grep -qx "targetSdkVersion:'33'" <<< "$badging"
entries=$(unzip -Z1 "$apk")
grep -qx 'classes.dex' <<< "$entries"
if grep -Ei '\.gguf$' <<< "$entries"; then
    echo 'Unexpected model asset' >&2
    exit 1
fi
libs=$(grep -E '^lib/.*\.so$' <<< "$entries")
printf '%s\n' "$libs"
for abi in arm64-v8a x86_64; do
    grep -Fqx "lib/$abi/libandroidx.graphics.path.so" <<< "$libs"
    grep -Fqx "lib/$abi/libforge_inference.so" <<< "$libs"
done
if grep -vE '^lib/(arm64-v8a|x86_64)/(libandroidx\.graphics\.path|libforge_inference)\.so$' <<< "$libs"; then
    echo 'Unexpected native library' >&2
    exit 1
fi
printf '%s\n' 'APK contains DEX and ARM64 / x86_64 inference libraries, with no GGUF files.'
"$tools/apksigner" verify --verbose --print-certs "$apk"
stat -c 'Bytes: %s' "$apk"
sha256sum "$apk"
