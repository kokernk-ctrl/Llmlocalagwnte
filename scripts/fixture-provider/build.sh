#!/usr/bin/env bash
# Run from this directory; outputs only under build/ and /tmp.
set -euo pipefail
sdk=${ANDROID_SDK_ROOT:-/home/exedev/android-toolchain/sdk}
bt="$sdk/build-tools/35.0.0"
platform="$sdk/platforms/android-35/android.jar"
tmp=$(mktemp -d /tmp/forge-fixture-provider.XXXXXXXX)
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp"/{assets,classes,dex} build
python3 make_fixtures.py "$tmp/assets"
javac --release 8 -classpath "$platform" -d "$tmp/classes" Fixtures.java
"$bt/d8" --lib "$platform" --min-api 26 --output "$tmp/dex" "$tmp"/classes/dev/forge/fixtureprovider/*.class
"$bt/aapt2" link -I "$platform" --manifest AndroidManifest.xml -A "$tmp/assets" -o "$tmp/unsigned.apk"
jar uf "$tmp/unsigned.apk" -C "$tmp/dex" classes.dex
"$bt/zipalign" -f 4 "$tmp/unsigned.apk" "$tmp/aligned.apk"
key=${DEBUG_KEYSTORE:-"$HOME/.android/debug.keystore"}
if [[ ! -f "$key" ]]; then
    key=build/debug.keystore
    [[ -f "$key" ]] || keytool -genkeypair -keystore "$key" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -validity 10000 -dname CN=AndroidDebug
fi
apk=build/forge-fixture-provider-debug.apk
"$bt/apksigner" sign --ks "$key" --ks-key-alias androiddebugkey --ks-pass pass:android \
    --key-pass pass:android --out "$apk" "$tmp/aligned.apk"
"$bt/apksigner" verify "$apk"
realpath "$apk"
