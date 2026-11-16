"""A stand-in `claude` executable for the real-adapter tests. Never calls the API.

Driven by environment variables:
- `STUB_CLAUDE_MODE`: `success` (default), `timeout` (spawns a child, then sleeps) or `garbage`.
- `STUB_CLAUDE_WRITE`: a path, relative to the cwd, to write before answering.
- `STUB_LOG`: a file that gets one JSON line per call (argv, cwd, stdin, selected env).
- `STUB_CHILD_PID`: in `timeout` mode, the file that receives the child's pid.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path


def main() -> int:
    stdin = sys.stdin.read()
    log = os.environ.get("STUB_LOG")
    if log:
        env = {k: v for k, v in os.environ.items() if k.startswith(("SDLC_", "STUB_", "TMPDIR"))}
        env["PATH"] = os.environ.get("PATH", "")
        line = {"tool": "claude", "argv": sys.argv[1:], "cwd": os.getcwd(), "stdin": stdin}
        with open(log, "a", encoding="utf-8") as f:
            f.write(json.dumps({**line, "env": env}) + "\n")
    mode = os.environ.get("STUB_CLAUDE_MODE", "success")
    if mode == "garbage":
        print("this is not json")
        return 1
    if mode == "timeout":
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(300)"])
        Path(os.environ["STUB_CHILD_PID"]).write_text(str(child.pid), encoding="utf-8")
        time.sleep(300)
        return 0
    write = os.environ.get("STUB_CLAUDE_WRITE")
    if write:
        target = Path.cwd() / write
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("written by the claude stub\n", encoding="utf-8")
    result = {
        "type": "result",
        "subtype": "success",
        "is_error": False,
        "num_turns": 2,
        "total_cost_usd": 0.01,
        "permission_denials": [],
        "result": 'Stub done.\n\n```json\n{"status": "done"}\n```',
    }
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
