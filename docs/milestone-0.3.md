# Milestone 0.3 · Real agent writes, local shell, bundled Python

Scope notes and verified facts for the 0.3.0 work. Package `dev.forgeai`, `versionCode 3`,
`versionName 0.3.0`, minSdk 26, targetSdk 33, ABIs `arm64-v8a` and `x86_64`.

## What 0.3.0 adds

- **Agent with real file writes (SAF).** Folders are granted individually as read-only or
  read+write via the Android folder picker with persistable grants. The agent plans with
  the local model when one is loaded and idle, then runs a fixed tool set — `list_files`,
  `read_file`, `search_text`, `write_file`, `append_file`, `count_lines`, `hash_file`,
  `run_check` — with per-task budgets (12 steps, 6 writes, 24 reads, 4 checks, 3 minutes).
  Two modes per task: **review** (diff shown, user approves before any write) and
  **auto-apply** (objective, folder and writes pre-authorized; diffs shown afterwards).
  Every write is re-read to verify content; replaced files keep a backup copy in
  app-private storage; concurrent modification is detected by comparing the bytes the
  agent last saw, and nothing is overwritten on conflict. Tool calls are structured JSON
  validated against scope and budgets; document paths are resolved by directory queries,
  never accepted as raw identifiers from model text.
- **Checkpoints.** Task state (phase, steps, pending writes, applied files, check results)
  is persisted as JSON in app-private storage after every state change. On restart,
  non-terminal tasks are surfaced as interrupted — never as completed.
- **Manual editor.** Files opens, previews, edits, diffs and saves authorized text files,
  or exports a copy into the same folder. Saving verifies by re-read.
- **Shell tab.** Whitelisted local read-only commands (`ls cat head tail wc grep stat
  md5sum sha256sum sort uniq find du echo date` for the user; a smaller read-only subset
  for the agent) run through ProcessBuilder with argv vectors — no shell interpreter.
  Flags are whitelisted per command, paths must stay inside a per-task working copy
  (mirrored from SAF into app-private storage), timeout 1–120 s, output capped at 256 KiB,
  cancel destroys the process. Agent-run checks are separated from user commands.
- **Python without Termux.** Chaquopy 17.2.0 bundles CPython 3.10 into the APK
  (arm64-v8a + x86_64). Predefined tools (JSON summary, CSV summary, text stats, JSONL
  count) and free scripts run with: 30 s cooperative deadline (`sys.settrace` guard), 64 KiB
  output cap, captured stdout/stderr/exceptions, script and input size caps, and explicit
  user approval per free script. The app declares **no INTERNET permission**, so network
  access is impossible at OS level. Limitations stated in the UI: C-level loops cannot be
  interrupted by the trace guard; memory allocation is not hard-enforced; package
  installation is not provided (no pip in-app).

## Honest limitations

- SAF document replacement is not atomic. The app keeps a best-effort backup (≤1 MiB) of
  replaced files and reports write failure states; it never claims transactional safety.
- Creating a file via `DocumentsContract.createDocument` followed by a truncated write
  can leave a partial file if the process dies mid-write; create failures are rolled back
  by deleting the created document when possible.
- The agent only creates files in existing folders; there is no mkdir tool in 0.3.0.
- Python free-script execution is experimental and approval-gated; predefined tools are
  the supported path. If the bundled runtime is absent, the Python tab says so and stays
  disabled rather than pretending.

## Verified locally (Linux host, JDK 21.0.13.1 Temurin, SDK 35 / Build-Tools 35.0.0 /
NDK 28.2.13676358 / CMake 3.22.1, llama.cpp pinned b6000)

- `:app:compileDebugKotlin`, `:app:assembleDebug`, `:app:testDebugUnitTest`,
  `:app:lintDebug` all pass.
- Unit tests cover the unified diff (validated against `git diff --no-index` reference
  output for modification, insertion, deletion and oversized-file fallback), the strict
  JSON parser and extractor used for model tool calls, the shell whitelist (rejected
  commands, flags, traversal, control characters), the deterministic planner (create
  names, never-overwrite rule, append target, budgets) and the audit log.
- `scripts/verify_apk.sh` passes on the debug APK: badging (`dev.forgeai`, 0.3.0, minSdk
  26, targetSdk 33), exactly the expected native libraries per ABI (inference plus Python
  runtime), and no `.gguf` assets.
- Host JNI smoke (`scripts/smoke_native_host.sh`, `-Xcheck:jni`) with the real
  `Qwen/Qwen2.5-0.5B-Instruct-GGUF` `qwen2.5-0.5b-instruct-q4_k_m.gguf`
  (491400032 bytes, SHA-256 `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`,
  hash verified before use): **PASS** — streamed arithmetic answer and **PASS** unload/reload
  through the exact `forge_jni.cpp`/pinned llama.cpp that ships in the APK.
- Lint found a real defect during this milestone: `takePersistableUriPermission` was being
  passed `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`, which Android rejects at runtime
  (`WrongConstant`). Fixed by taking only READ/WRITE bits; persistability is requested on
  the picker intent.

## Pending on-device verification (required before claiming device runtime support)

No Android device or emulator was available in the build environment used for this
milestone (no `/dev/kvm`; software emulation impractical on 2 vCPU). The following must
be validated on an Android 13 device before the main flow is claimed device-verified:

1. Import a Qwen2.5 GGUF, load it in Chat and generate a reply on device.
2. Grant a read+write folder; run an agent task in auto mode that creates one file and
   edits another; confirm the changes from the system file manager or ADB.
3. Conflict test: modify the target file externally between plan and apply; confirm the
   agent reports a conflict and does not overwrite.
4. Non-authorized folder and revoked-grant tests: writes rejected, no new operations after
   revocation, started writes reported.
5. Shell command and Python tool executed on device with timeout and cancel exercised.
6. Kill the app mid-task, restart, and confirm the checkpoint shows interrupted (not done).

## Tool-call protocol

The planning prompt fixes the contract: a short plan, then one JSON object per line —
`{"tool": "...", "args": {...}}`. The runtime extracts balanced JSON objects from model
output, rejects unknown tools and invalid arguments, counts every call against budgets,
and falls back to the labeled deterministic plan when no model is loaded or the output is
unparsable. The plan source is displayed verbatim in the UI ("local model (N tool calls)"
or "deterministic fallback").
