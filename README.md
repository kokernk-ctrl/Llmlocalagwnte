# FORGE AI · Local Android workstation

FORGE AI provides an offline GGUF inventory, local Qwen text chat, and a read-only workspace. It does not require an account or API key. This release does not run commands, modify projects, or include an autonomous agent.

## Install and use

- Download the signed APK from [GitHub Releases](https://github.com/kokernk-ctrl/Llmlocalagwnte/releases). Android 8.0+ (API 26), `arm64-v8a` or `x86_64`, package `dev.forgeai`.
- **Chat:** in **Models**, import a small Qwen2/Qwen2.5 Instruct GGUF and tap **Set preferred**. Open **Chat**, tap **Load model**, then write and send a message. Use **Stop** to interrupt generation and **Unload** to free memory and clear the chat. There is no model bundled or automatic download. The preferred model is copied into private storage when loading, so budget free space for a second copy.
- **Files:** consent, pick one folder, list and preview UTF-8 text files, and search a preview for literal text. **Use in Chat** fills an unsent draft for review. Chat does not have access to the workspace. Revoke access in Files. The capped audit log records operation types and timestamps, not paths or content.
- **Models:** Refresh rechecks the grant, availability and reported size. The metadata parser supports a broader set of tags than Chat does, and is not a full integrity checker. Remove unregisters a model without touching the source file.

## Build and test locally

Use JDK 21, Android SDK Platform 35, Build Tools 35.0.0, NDK `28.2.13676358` and CMake `3.22.1`. Point `ANDROID_HOME` or an untracked `local.properties` at the SDK.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Artifacts: `app/build/outputs/apk/debug/app-debug.apk` and, with `assembleRelease`, `app/build/outputs/apk/release/app-release-unsigned.apk`. A debug APK does not upgrade the signed GitHub release. `scripts/verify_apk.sh` verifies a signed artifact. `scripts/smoke_native_android.sh` exercises JNI on a dedicated emulator with an externally provided test model. Notes and evidence are in `docs/milestone-0.2.md`.

The Gradle build compiles pinned `llama.cpp` source with a checked archive hash via CMake. No model weights are downloaded by the build, embedded, or uploaded. CPU inference is local; model files can come from Android document providers. Workspace access is read-only. Workspace tools and chat do not execute any instructions from file contents.
