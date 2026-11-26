"""FORGE AI task runner (bundled by Chaquopy from src/main/python).

Runs explicitly approved scripts with cooperative limits:
- deadline enforced via sys.settrace line events (pure-Python code; C-level loops
  cannot be interrupted, and this limitation is reported to the user);
- stdout/stderr captured through capped stream objects;
- exceptions returned as text, never raised into the app process;
- network is impossible at OS level: the app declares no INTERNET permission.
"""
import io
import sys
import time
import traceback

MAX_OUTPUT = 65536
MAX_DEADLINE_S = 60.0


class CappedStream(io.StringIO):
    """Collects output up to a byte cap; extra output is dropped and flagged."""

    def __init__(self):
        super().__init__()
        self.truncated = False

    def write(self, s):  # noqa: D102
        if self.tell() + len(s) > MAX_OUTPUT:
            room = MAX_OUTPUT - self.tell()
            self.truncated = True
            if room > 0:
                super().write(s[:room])
            return len(s)
        return super().write(s)


def run(code, task_input, deadline_s=None):
    """Executes `code` and returns a status dict. Never raises."""
    if not isinstance(code, str) or not code.strip():
        return {"ok": False, "error": "empty script", "stdout": "", "stderr": "", "truncated": False}
    deadline = time.monotonic() + min(float(deadline_s or MAX_DEADLINE_S), MAX_DEADLINE_S)
    out, err = CappedStream(), CappedStream()
    old_out, old_err = sys.stdout, sys.stderr
    sys.stdout, sys.stderr = out, err

    def guard(frame, event, arg):
        if event == "line" and time.monotonic() > deadline:
            raise TimeoutError("script exceeded its time budget")
        return guard

    result = {"ok": True, "error": "", "stdout": "", "stderr": "", "truncated": False}
    scope = {"__name__": "__forge_task__", "INPUT": task_input if isinstance(task_input, str) else ""}
    try:
        sys.settrace(guard)
        try:
            exec(compile(code, "<forge-task>", "exec"), scope)  # noqa: S102 - explicit user approval required
        finally:
            sys.settrace(None)
    except TimeoutError as e:
        result["ok"] = False
        result["error"] = str(e)
    except BaseException:
        result["ok"] = False
        result["error"] = traceback.format_exc(limit=12)
    finally:
        sys.stdout, sys.stderr = old_out, old_err
        result["stdout"] = out.getvalue()
        result["stderr"] = err.getvalue()
        result["truncated"] = out.truncated or err.truncated
    return result
