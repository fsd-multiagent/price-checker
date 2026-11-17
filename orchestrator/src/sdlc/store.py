"""The run dir: `state.json` (one writer, atomic) and `events.jsonl` (append-only). AD-4.

Also the usage ledger `<runs-dir>/usage.jsonl` (AD-24): a projection of the usage records in
every run's `state.json`, keyed by `(run_id, seq)`. Only this module writes it: a run's own
`save` (and its resume-time `load`) appends, and only `rebuild_ledger` rewrites. Reports read
the records from each run's `state.json` and never write.
"""

from __future__ import annotations

import os
import sys
import tempfile
from collections.abc import Callable, Iterable
from pathlib import Path

from pydantic import BaseModel, ValidationError

from sdlc.core.models import Pipeline, RunState, Status, UsageRecord
from sdlc.core.state import backfill_usage
from sdlc.core.usage import dedupe, state_records

LEDGER = "usage.jsonl"

Warn = Callable[[str], None]


def _stderr(message: str) -> None:
    print(f"sdlc: warning: {message}", file=sys.stderr)


class LedgerBusy(RuntimeError):
    """`--rebuild` refused: a run is still running and would append while the file is rewritten."""


def ledger_path(runs_dir: str | Path) -> Path:
    return Path(runs_dir) / LEDGER


def read_ledger(path: str | Path) -> tuple[UsageRecord, ...]:
    """Every ledger row, deduplicated by key. A torn or unreadable line is skipped, and unknown
    fields are ignored (AD-24)."""
    try:
        text = Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ()
    rows: list[UsageRecord] = []
    for line in text.splitlines():
        if not line.strip():
            continue
        try:
            rows.append(UsageRecord.model_validate_json(line))
        except (ValidationError, ValueError):
            continue
    return dedupe(rows)


def _append_lines(path: Path, records: Iterable[UsageRecord]) -> None:
    """One `O_APPEND` write plus `fsync`. A torn last line is closed off first, so it stays a
    line of its own that readers skip."""
    blob = "".join(r.model_dump_json() + "\n" for r in records)
    if not blob:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o644)
    try:
        size = os.fstat(fd).st_size
        if size:
            with path.open("rb") as f:
                f.seek(size - 1)
                if f.read(1) != b"\n":
                    blob = "\n" + blob
        os.write(fd, blob.encode("utf-8"))
        os.fsync(fd)
    finally:
        os.close(fd)


class RunStore:
    def __init__(
        self, run_dir: str | Path, ledger: str | Path | None = None, warn: Warn = _stderr
    ) -> None:
        self.dir = Path(run_dir)
        self.ledger = Path(ledger) if ledger is not None else ledger_path(self.dir.parent)
        self.warn = warn
        self._in_ledger: set[int] | None = None  # this run's seqs already in the ledger

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
        self.reconcile(state)

    def load(self, pipeline: Pipeline | None = None, *, reconcile: bool = False) -> RunState:
        """Read `state.json`; with a pipeline, backfill pre-AD-24 usage records (in memory).
        Only the run itself passes `reconcile` (on resume): any final record missing from the
        ledger is then appended. Reports never write the ledger."""
        state = RunState.model_validate_json(self.state_path.read_text(encoding="utf-8"))
        if pipeline is not None:
            state = backfill_usage(pipeline, state)
        if reconcile:
            self.reconcile(state)
        return state

    def reconcile(self, state: RunState) -> None:
        """AD-24: append every final usage record of `state` that the ledger lacks."""
        records = state_records(state)
        if not records:
            return
        if self._in_ledger is None:
            self._in_ledger = {r.seq for r in read_ledger(self.ledger) if r.run_id == state.run_id}
        missing = [r for r in records if r.seq not in self._in_ledger]
        if not missing:
            return
        try:
            _append_lines(self.ledger, missing)
        except OSError as exc:  # state.json is saved; the next save or load retries
            self.warn(f"could not append to the usage ledger {self.ledger}: {exc}")
            return
        self._in_ledger.update(r.seq for r in missing)

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


PipelineFor = Callable[[Path], Pipeline | None]


def load_states(
    runs_dir: str | Path,
    run_ids: Iterable[str] | None = None,
    pipeline_for: PipelineFor | None = None,
    warn: Warn = _stderr,
) -> list[RunState]:
    """Read-only: the runs' states, backfilled in memory with `pipeline_for(state_path)` when it
    gives a pipeline. A corrupt or invalid `state.json` is skipped with a warning."""
    root = Path(runs_dir)
    states: list[RunState] = []
    for run_id in list_runs(root) if run_ids is None else run_ids:
        state_path = root / run_id / "state.json"
        try:
            state = RunState.model_validate_json(
                state_path.read_text(encoding="utf-8", errors="replace")
            )
        except (OSError, ValueError) as exc:
            warn(f"skipping run {run_id}: cannot read {state_path}: {str(exc).splitlines()[0]}")
            continue
        pipeline = pipeline_for(state_path) if pipeline_for is not None else None
        states.append(backfill_usage(pipeline, state) if pipeline is not None else state)
    return states


def rebuild_ledger(
    runs_dir: str | Path, pipeline_for: PipelineFor | None = None, warn: Warn = _stderr
) -> int:
    """`sdlc usage --rebuild`: regenerate the ledger from every run's `state.json`, backfilled
    with the pipeline `pipeline_for(state_path)` gives, if any. Written atomically; returns the
    number of rows. Refuses (LedgerBusy) while a run is running: it would append meanwhile."""
    root = Path(runs_dir)
    states = load_states(root, None, pipeline_for, warn)
    running = [s.run_id for s in states if s.status is Status.RUNNING]
    if running:
        raise LedgerBusy(
            f"run(s) {', '.join(running)} still running; rebuild the ledger once they stop"
        )
    records = [r for s in states for r in state_records(s)]
    path = ledger_path(root)
    root.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=root, prefix=".usage.", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write("".join(r.model_dump_json() + "\n" for r in records))
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    except BaseException:
        Path(tmp).unlink(missing_ok=True)
        raise
    return len(records)
