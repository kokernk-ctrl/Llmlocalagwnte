# FORGE AI · Local Android workstation

FORGE AI provides an offline GGUF inventory, local Qwen text chat, an agent that reads and
writes real project files inside folders you authorize, a whitelisted local shell, and a
bundled CPython runtime. It does not require an account or API key, has no INTERNET
permission, and does not embed or download model weights.

## Install and use

- Download the signed APK from [GitHub Releases](https://github.com/kokernk-ctrl/Llmlocalagwnte/releases). Android 8.0+ (API 26), `arm64-v8a` or `x86_64`, package `dev.forgeai`.
- **Models:** import a small Qwen2/Qwen2.5 Instruct GGUF and tap **Set preferred**.
- **Chat:** tap **Load model**, write and send a message. **Stop** interrupts generation and **Unload** frees memory. The preferred model is copied into private storage while loaded, so budget free space for a second copy.
- **Agent:** grant a folder as read+write in **Files**, describe the objective, choose review-before-apply or auto-apply within scope, and run. The agent lists, reads, creates and edits real files, verifies each write by re-read, keeps backups of replaced files, detects concurrent changes without overwriting, runs whitelisted local checks, and checkpoints progress so interrupted tasks are reported after restart. Diffs and evidence are shown; budget limits are fixed (12 steps, 6 writes, 24 reads, 4 checks, 3 minutes).
- **Shell:** whitelisted read-only commands (`wc`, `grep`, `sha256sum`, …) run on a working copy of an authorized folder with argv vectors, per-command flag whitelists, timeouts and output caps. No shell interpreter is spawned.
- **Python:** bundled CPython (Chaquopy). Predefined JSON/CSV/text tools run freely on loaded input; free scripts need explicit per-script approval and run with time/output limits. Network is impossible at OS level.
- **Files:** browse, preview, edit with diff, save (verified) or export copies. Folders are granted and revoked individually; the audit log records action types and times only.
- **Access:** explains every grant and how to revoke it. The app never asks for broad storage access and cannot reach other apps' private data.

## Build and test locally

Use JDK 21, Android SDK Platform 35, Build Tools 35.0.0, NDK `28.2.13676358` and CMake `3.22.1`. Point `ANDROID_HOME` or an untracked `local.properties` at the SDK.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Artifacts: `app/build/outputs/apk/debug/app-debug.apk` and, with `assembleRelease`, `app/build/outputs/apk/release/app-release-unsigned.apk`. A debug APK does not upgrade the signed GitHub release. `scripts/verify_apk.sh` verifies a signed artifact. `scripts/smoke_native_host.sh` exercises the JNI layer on the Linux host with an externally provided model; `scripts/smoke_native_android.sh` covers a dedicated emulator. Notes and evidence are in `docs/milestone-0.3.md`.

The Gradle build compiles pinned `llama.cpp` source with a checked archive hash via CMake, and bundles CPython for Android via Chaquopy 17.2.0 (license in `app/src/main/assets/licenses/`). No model weights are downloaded by the build, embedded, or uploaded. CPU inference is local. Folder access is granted through SAF per folder, read-only or read+write; writes are verified by re-read and never presented as atomic. The app declares no INTERNET permission.
