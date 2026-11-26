#!/usr/bin/env bash
set -euo pipefail
apk=${1:?Usage: smoke_native_android.sh APK DEVICE_MODEL_PATH [SDK_PATH]}
model=${2:?Path to test GGUF on the Android emulator}
[[ $model =~ ^/[-A-Za-z0-9./_]+$ ]]
sdk=${3:-${ANDROID_HOME:?Set ANDROID_HOME or pass SDK path}}
adb=$sdk/platform-tools/adb
serial=${ANDROID_SERIAL:-emulator-5554}
[[ $serial =~ ^emulator-[0-9]+$ ]]
abi=$("$adb" -s "$serial" shell getprop ro.product.cpu.abi)
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
script_dir=$(dirname "$0")/android-jni-smoke
javac --release 8 -d "$work" "$script_dir/NativeBridge.java"
"$sdk/build-tools/35.0.0/d8" --min-api 26 --lib "$sdk/platforms/android-35/android.jar" --output "$work" "$work/dev/forgeai/intelligence/"*.class
unzip -p "$apk" "lib/$abi/libforge_inference.so" > "$work/libforge_inference.so"
"$adb" -s "$serial" push "$work/classes.dex" "$work/libforge_inference.so" /data/local/tmp/
"$adb" -s "$serial" shell "CLASSPATH=/data/local/tmp/classes.dex app_process /system/bin dev.forgeai.intelligence.NativeBridge /data/local/tmp/libforge_inference.so $model"
