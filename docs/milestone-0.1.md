# Milestone 0.1 checkpoint

Objective: build and verify an installable Android 13-compatible debug APK implementing a real offline GGUF registry, not a native inference engine.

## Environment and dependencies

- Ubuntu 24.04.5 LTS x86_64; 2 vCPUs; 7.7 GiB RAM; 18 GiB available on initial inspection. `/dev/kvm` absent. HTTPS to Google Android SDK/Maven, Maven Central and Gradle verified.
- OpenJDK 21.0.13.1, Gradle wrapper 8.10.2 (distribution SHA-256 pinned), Android Gradle Plugin 8.8.2; Kotlin and the Compose compiler plugin 2.1.10, JVM target 17.
- Local SDK `/home/exedev/android-toolchain/sdk`: command-line tools 12.0, platform API 35 (rev 2), Build Tools 35.0.0, Platform Tools 37.0.3. API 33 emulator system image rev 2 (x86_64), emulator 37.3.10.0.
- AndroidX Activity Compose 1.10.1; Compose BOM 2025.02.00 (foundation and Material 3); Lifecycle 2.8.7; JUnit 4.13.2; Android SQLite and Storage Access Framework (no Room dependency).
- Host CMake 3.28.3, GCC 13.3.0. NDK and Clang were not found; no native build was needed or configured in this milestone.

## Files added and architecture

- Root Gradle settings, wrapper, and `app/build.gradle.kts`: reproducible Kotlin/Compose app configuration. `local.properties` is VM-specific and untracked.
- `app/src/main/AndroidManifest.xml`, `res/values/styles.xml`, `res/drawable/forge_icon.xml`, `MainActivity.kt`: app entry point, resources and dark shell.
- `ui/navigation/{Destination,ForgeApp}.kt`, `ui/theme/ForgeTheme.kt`, `ui/screens/{OverviewScreens,ModelsScreen}.kt`: dashboard, navigation, document import, status, removal confirmation and access information.
- `core/ForgeViewModel.kt` and `models/{ModelEntry,ModelRegistry,GgufMetadata}.kt`: serialized background I/O, SQLite inventory, read-only SAF grants, one preferred model and bounded GGUF metadata verification. No filesystem aliasing or document writes.
- `intelligence/LocalInference.kt`: future loader/stream/cancel boundary. No implementation in this milestone.
- `app/src/test/java/dev/forgeai/models/GgufMetadataTest.kt` and `scripts/smoke_api33.py`: parser unit tests and emulator workflow checks.
- `README.md`, this checkpoint, and local `dist/` delivery artifacts: installation, build, scope and verification evidence.

## Next work

Implement 0.2 with explicit terms consent, folder-scoped SAF, read-only browsing first, and a redacted activity log. Implement 0.3 only with a pinned and tested llama.cpp revision, Android NDK, JNI lifetime and cancellation tests, and actual ARM64 Qwen model loading/generation under a conservative context. Preserve the distinction between a registry preference and a loaded model. Later modules remain unimplemented.

## Build evidence

- `./gradlew assembleDebug testDebugUnitTest lintDebug --console=plain`: successful.
- GGUF parser suite: 9 passed, 0 failed, 0 skipped. Test cases use generated fixtures, not runnable Qwen weights.
- Android lint: 0 errors, 6 warnings (see `dist/lint-results-debug.txt`).
- `scripts/verify_apk.sh`: confirmed application ID `dev.forgeai`, min SDK 26, target SDK 33, DEX, Android debug signature (v2), no model assets, and AndroidX graphics libraries for arm64-v8a, armeabi-v7a, x86 and x86_64. No project-built JNI or inference library.
- Delivery: `dist/FORGE-AI-0.1.0-debug.apk`, 8,773,749 bytes, SHA-256 `e32ea2afa23520c2399fe4a14ea56208905144d7cfc8325f97f805636428cc5b`.
- Emulator installation: `adb install -r` succeeded on the Android 13 API 33 x86_64 AVD. The first UI smoke attempt was blocked by the emulator's shared storage mount; after storage mounted, Android System UI ANRs and an activity launch timeout blocked verification. No physical device or ARM64 runtime test was performed. See `dist/android33-smoke.txt`.
- Follow-up: after dismissing the emulator System UI alert and reducing its display resolution, API 33 runtime checks passed for launch, navigation, malformed GGUF rejection, valid SAF import, readability, preferred selection retention across process restart, removal confirmation/cancellation, and preservation of the source file. The automated driver covered through rejection; the remaining checks were verified interactively with ADB/UI Automator. See `dist/android33-manual-verification.txt`. The Qwen fixture is header-valid but not a usable model.
