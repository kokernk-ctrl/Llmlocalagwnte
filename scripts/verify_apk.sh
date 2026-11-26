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
expected_libs='libandroidx.graphics.path.so
libchaquopy_java.so
libcrypto_chaquopy.so
libcrypto_python.so
libforge_inference.so
libpython3.10.so
libpython3.so
libsqlite3_chaquopy.so
libsqlite3_python.so
libssl_chaquopy.so
libssl_python.so'
for abi in arm64-v8a x86_64; do
    while IFS= read -r so; do
        grep -Fqx "lib/$abi/$so" <<< "$libs" || { echo "Missing lib/$abi/$so" >&2; exit 1; }
    done <<< "$expected_libs"
done
unexpected=$(grep -vE '^lib/(arm64-v8a|x86_64)/(libandroidx\.graphics\.path|libchaquopy_java|libcrypto_chaquopy|libcrypto_python|libforge_inference|libpython3\.10|libpython3|libsqlite3_chaquopy|libsqlite3_python|libssl_chaquopy|libssl_python)\.so$' <<< "$libs" || true)
if [ -n "$unexpected" ]; then
    printf '%s\n' "$unexpected"
    echo 'Unexpected native library' >&2
    exit 1
fi
printf '%s\n' 'APK contains DEX plus ARM64 / x86_64 inference and Python runtime libraries, with no GGUF files.'
"$tools/apksigner" verify --verbose --print-certs "$apk"
stat -c 'Bytes: %s' "$apk"
sha256sum "$apk"
