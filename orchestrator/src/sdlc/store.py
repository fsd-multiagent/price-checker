"""The run dir: `state.json` (one writer, atomic) and `events.jsonl` (append-only). AD-4."""

from __future__ import annotations

import os
import tempfile
from pathlib import Path

from pydantic import BaseModel

from sdlc.core.models import RunState


class RunStore:
    def __init__(self, run_dir: str | Path) -> None:
        self.dir = Path(run_dir)

    @property
    def state_path(self) -> Path:
        return self.dir / "state.json"

    @property
    def events_path(self) -> Path:
        return self.dir / "events.jsonl"

    def create(self) -> None:
        self.dir.mkdir(parents=True, exist_ok=False)
        for sub in ("steps", "verdicts", "escalations"):
            (self.dir / sub).mkdir()

    def save(self, state: RunState) -> None:
        """Write to a temp file, fsync, then rename over `state.json`."""
        data = state.model_dump_json(indent=2)
        fd, tmp = tempfile.mkstemp(dir=self.dir, prefix=".state.", suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(data)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, self.state_path)
        except BaseException:
            Path(tmp).unlink(missing_ok=True)
            raise

    def load(self) -> RunState:
        return RunState.model_validate_json(self.state_path.read_text(encoding="utf-8"))

    def append_event(self, event: BaseModel) -> None:
        with self.events_path.open("a", encoding="utf-8") as f:
            f.write(event.model_dump_json() + "\n")
            f.flush()
            os.fsync(f.fileno())

    def write_artifact(self, rel: str, text: str) -> str:
        """Write a log, verdict or patch inside the run dir; returns the run-dir-relative path."""
        path = self.dir / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return rel

    def read_artifact(self, rel: str) -> str | None:
        try:
            return (self.dir / rel).read_text(encoding="utf-8")
        except FileNotFoundError:
            return None


def list_runs(runs_dir: str | Path) -> list[str]:
    root = Path(runs_dir)
    if not root.is_dir():
        return []
    return sorted(p.name for p in root.iterdir() if (p / "state.json").is_file())
