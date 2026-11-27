# Milestone 0.4.0 — Agent in a single conversation

## What changed relative to 0.3.0

### The conversation is the app
- The app opens directly on **Chat**. The separate Agent tab was removed; the agent
  lives in the conversation.
- Header shows the **active model**, the **active folder** (with effective permission)
  and the **edit mode** (review / auto), each switchable without leaving the chat.
- Shell, Python, Files/editor are **tools of the agent** invoked inside the
  conversation; their manual screens survive as secondary options under "Tools".
- The conversation is a timeline: objective, plan note, every real action (collapsible
  details), proposed writes with diffs, approval requests with inline
  Approve/Refuse buttons, command results (stdout/stderr/exit), errors and the final
  report. Multiple requests in the same chat continue the same conversation.

### Model-driven tool loop
- When a model is loaded, the engine runs a multi-turn loop: the model answers with
  exactly one structured tool call per turn (`{"tool":...,"args":{...}}`), the runtime
  validates scope/arguments/budgets, executes for real and feeds the actual result
  back; the loop ends with a plain-text answer. Model text is never treated as proof
  of execution. Invalid outputs execute nothing.
- Without a model (or when the model becomes unavailable on the first turn) a clearly
  labeled deterministic plan runs instead.
- New agent tools: `python_tool` (json/csv/text/jsonl analysis on a folder file) and
  `python_script` (free-form code, always requiring explicit in-chat approval even in
  auto mode).

### v0.3.0 defect: root causes (reproduced in `AgentEngineTest`)
1. **`wc` exit 1 on a file that did not exist** — the tool loop executed `run_check`
   before writes were applied (in review mode nothing is written until approval; the
   check ran against a working copy that never contained the planned file).
2. **`verify` shown as ok with "verification FAILED" inside** — the verify step
   hardcoded `ok=true`; failures were reported as text only.
3. **`forge-task-notes.md` vs `forge-task-notes.md.txt`** — `createFile` was called
   with MIME `text/plain` for a `.md` name; the provider stored the document with an
   appended `.txt`. `applyAndFinish` discarded the returned entry, so the controller
   kept tracking the planned name, verification re-read the wrong path
   (WriteException) and the *next* task then saw `forge-task-notes.md.txt` as an edit
   target while planning another `forge-task-notes.md` — two distinct documents.

### Fixes
- **Flow order:** a check that depends on a not-yet-applied write is reported as
  `awaiting approval` and does not run. After approval (or in auto mode) the write is
  applied, verified by re-read, the **working copy is updated with the verified bytes
  under the real name** and only then does the check execute. If the save failed the
  dependent check is **skipped with the concrete reason** and the task is never
  summarized as verified.
- **Real names:** `createFile` now receives the extension-appropriate MIME
  (`text/markdown` for `.md`, …) to avoid provider renames, and the engine treats the
  returned document as the source of truth: provider renames are recorded
  (`providerPath`), surfaced in the timeline and used by every later step (checks,
  mirror, report).
- **Honest state machine:** every step carries an explicit outcome — writes:
  `PROPOSED, SAVED, SAVE_FAILED, VERIFIED, VERIFY_FAILED, REFUSED, CANCELLED,
  RUN_OK, RUN_FAILED`; checks: `PASSED, FAILED, AWAITING_WRITE, SKIPPED,
  NOT_EXECUTED, CANCELLED`. A failed verification or check can no longer be reported
  as ok; the final report lists counts per state and the concrete errors.
- **Deterministic planner:** gated by the objective's verbs (nothing is created or
  edited unless asked); default names avoid stem collisions with leftover renamed
  artifacts; edit targets match by name or stem ("update the README" → README.md) and
  never pick a stale `forge-task-notes*` artifact; a "check <file>" intent verifies
  existing files.
- **Persistence:** checkpoints are versioned (v2). Old 0.3.0 checkpoints fail the
  version check and are ignored (their pending writes could not be trusted — that is
  the bug being fixed). Non-terminal states surface as *interrupted*, never as done.

## Known limitations (unchanged or new)
- SAF replacement is still not atomic; backups in app storage remain the recovery path.
- `python_script` runs on the shared Chaquopy runtime: a C-level infinite loop cannot
  be interrupted (cooperative trace guard + wall-clock guard only).
- The agent has no `mkdir` and no delete tool (deletions are deliberately not exposed).
- Model context per turn is compact (folder listing capped at 80 entries, progress
  digest capped at ~2.4 KB); very large folders are summarized, not fully listed.
