#!/usr/bin/env bash
# Exercise the exact JNI implementation and pinned dependency on Linux/JDK 11+.
set -euo pipefail
root=$(realpath "$(dirname "$0")/..")
usage='Usage: smoke_native_host.sh --build-only | [/path/to/qwen2.5-0.5b-instruct-q4_k_m.gguf]'
[[ $# == 1 ]] || { echo "$usage" >&2; exit 2; }
export JAVA_HOME=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
build=${FORGE_HOST_BUILD_DIR:-"$root/app/build/native-host-smoke"}
if [[ $1 != --build-only ]]; then
    [[ -f $1 ]] || { echo "No GGUF at: $1" >&2; exit 2; }
    model=$(realpath "$1")
fi
cmake -S "$root/app/src/main/cpp" -B "$build" -DCMAKE_BUILD_TYPE=Release
nice -n 10 cmake --build "$build" --target forge_inference --parallel 1
mkdir -p "$build/classes"
javac --release 11 -d "$build/classes" "$root/scripts/android-jni-smoke/NativeBridge.java" "$root/scripts/native-host-smoke/HostSmoke.java"
[[ $1 == --build-only ]] && exit 0
java -Xmx64m -Xcheck:jni -cp "$build/classes" dev.forgeai.intelligence.HostSmoke "$build/libforge_inference.so" "$model"
