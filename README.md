# FORGE AI · Local Android workstation

FORGE AI is an **offline coding agent that works in one conversation**: you pick a model and
a folder, grant what is needed, and describe the task in plain language. The agent plans,
calls tools, edits and verifies real project files inside folders you authorize — all inside
the same chat. It bundles a whitelisted local shell and a CPython runtime as agent tools. It
does not require an account or API key, has no INTERNET permission, and does not embed or
download model weights.

## Install and use

- Download the signed APK from [GitHub Releases](https://github.com/kokernk-ctrl/Llmlocalagwnte/releases). Android 8.0+ (API 26), `arm64-v8a` or `x86_64`, package `dev.forgeai`.
- The app opens **directly on the Chat**. The header shows the active model, the active folder
  (with its effective read/read+write permission) and the edit mode; switch them without
  leaving the conversation. Shell, Python, the file editor and model management are secondary
  tools under **Tools** — the agent already uses them inside the chat.
- **Models:** import a small Qwen2/Qwen2.5 Instruct GGUF in Models and tap **Set preferred**,
  then **Load model** in the chat header. Unloading frees memory; backgrounding the app
  unloads automatically under memory pressure.
- **Agent conversation:** write e.g. *"read the README, create a file with project notes,
  update the README with a reference to the notes and check both files"*. The timeline shows
  the plan, every real action (collapsible details), proposed writes with diffs, approvals
  with inline buttons, command results, errors and the final report. The model answers with
  one structured tool call per turn; the runtime validates scope, arguments and budgets and
  feeds the real result back. Without a model a clearly labeled deterministic plan runs.
- **Write modes:** *Review before apply* shows the diff of every change and writes nothing
  until you approve; steps that depend on a pending write are held back and only run after
  the file is really saved. *Auto-apply within scope* applies writes immediately in the
  pre-authorized folder and shows diffs and verification afterwards; free-form Python
  scripts always require explicit approval anyway. Writes are verified by re-read, keep a
  backup of replaced files and detect concurrent changes without overwriting. The storage
  provider may store a document under a different name — the agent tracks the real name and
  shows it. Checkpoints survive restarts and distinguish applied from pending.
- **Shell:** whitelisted read-only commands (`wc`, `grep`, `sha256sum`, …) run on a working
  copy of the authorized folder with argv vectors, per-command flag whitelists, timeouts and
  output caps — as the agent's `run_check` tool or as a manual screen. No shell interpreter.
- **Python:** bundled CPython (Chaquopy). The agent can analyze files (`json_summary`,
  `csv_summary`, `text_stats`, `jsonl_count`) via `python_tool`; free scripts require
  explicit per-script approval and run with time/output limits. Network is impossible at OS level.
- **Files:** browse, preview, edit with diff, save (verified) or export copies. Folders are
  granted and revoked individually; the audit log records action types and times only.
- **Access:** explains every grant and how to revoke it. The app never asks for broad
  storage access and cannot reach other apps' private data.

## Build and test locally

Use JDK 21, Android SDK Platform 35, Build Tools 35.0.0, NDK `28.2.13676358` and CMake `3.22.1`. Point `ANDROID_HOME` or an untracked `local.properties` at the SDK.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Artifacts: `app/build/outputs/apk/debug/app-debug.apk` and, with `assembleRelease`, `app/build/outputs/apk/release/app-release-unsigned.apk`. A debug APK does not upgrade the signed GitHub release. `scripts/verify_apk.sh` verifies a signed artifact. `scripts/smoke_native_host.sh` exercises the JNI layer on the Linux host with an externally provided model; `scripts/smoke_native_android.sh` covers a dedicated emulator. Notes and evidence are in `docs/milestone-0.4.md`.

The Gradle build compiles pinned `llama.cpp` source with a checked archive hash via CMake, and bundles CPython for Android via Chaquopy 17.2.0 (license in `app/src/main/assets/licenses/`). No model weights are downloaded by the build, embedded, or uploaded. CPU inference is local. Folder access is granted through SAF per folder, read-only or read+write; writes are verified by re-read and never presented as atomic. The app declares no INTERNET permission.
