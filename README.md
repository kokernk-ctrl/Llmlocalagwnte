# FORGE AI 0.1.0 · Model registry

FORGE AI is an offline Android workstation foundation. This milestone is a model inventory, **not** an inference engine or autonomous agent. It uses Kotlin and Jetpack Compose Material 3. The application declares no internet or broad storage permissions.

## Install
The signed APK from GitHub Actions is published under [GitHub Releases](https://github.com/kokernk-ctrl/Llmlocalagwnte/releases). The release and the debug build have different signing keys.

The locally built debug APK is `dist/FORGE-AI-0.1.0-debug.apk`. Install it with Android's package installer (allow installs from that source if prompted) or `adb install -r dist/FORGE-AI-0.1.0-debug.apk`. Package `dev.forgeai`; Android 8.0+ (API 26); target Android 13 (API 33). The APK includes AndroidX graphics support for arm64-v8a, armeabi-v7a, x86 and x86_64; there is no inference library. The debug signature is for testing, not production distribution.

## Use

1. Open **Models** → **Import GGUF**. In Android's picker select a local `.gguf` document with a provider-reported size and persistent read support.
2. The registry shows file size, Qwen/Llama architecture tag, declared quantization (if recognized), readability and selection. **Set preferred** changes only a stored selection; it does not load the file. **Refresh** rechecks the entry's grant, file availability and reported size, not its content hash. Re-select a document to revalidate its metadata.
3. **Remove** asks for confirmation, removes the SQLite entry and attempts to release the persistent grant. It never deletes or modifies the original file.
4. **Permissions** explains document access and revocation. No terms acceptance or workspace folder access is implemented.

## Build locally

Use JDK 21, Android SDK Platform 35 and Build Tools 35.0.0. Point `ANDROID_HOME` or untracked `local.properties` at the SDK, then run:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Evidence and checkpoint: `docs/milestone-0.1.md`. A smoke-test driver using ADB and UI Automator is in `scripts/smoke_api33.py`; run only on a dedicated unlocked API 33 emulator with an empty app registry.

## Architecture and limitations

- `ui/{navigation,screens,theme}` renders Dashboard, Model Manager and Permissions. `core/ForgeViewModel.kt` serializes actions and dispatches document and database I/O off the main thread.
- `models/ModelRegistry.kt` owns the SQLite schema, persisted read grants and availability checks. The DB stores references and metadata only. Removing a registration or uninstalling the app does not remove model weights.
- `models/GgufMetadata.kt` bounds header inspection to 64 MiB and accepts little-endian v2/v3 with `qwen2`, `qwen3` or `llama` tags. Split files are not supported. Validation walks metadata and the tensor directory, but does not verify full tensor data, model integrity or runtime compatibility. Provider reads can still be delayed, particularly for cloud documents.
- `intelligence/LocalInference.kt` defines the future JNI integration boundary; there is no implementation, fake generation, NDK build, or bundled model. The 4 GB profile is guidance, not a tested runtime memory budget.
- Workspace consent and audit, inference/chat, agent/tool execution, Python, project memory and continuity, multimodal input, orchestration, Model Lab and reports are later milestones and not represented by dummy screens.
