"""`sdlc run [--from <stage>] [--dry-run]`, `sdlc story|fix <issue> [--dry-run]`,
`sdlc status [<run-id>]`, `sdlc resume <run-id>`, `sdlc pause|stop [<run-id>]` (issue #72),
`sdlc usage [<run-id> | --all] [--by agent|stage|model|round|run] [--rebuild]` (AD-24).

Exit codes:
- 0: passed, paused (owner pause or stop; the queue is not drained), or queued behind the lock
- 1: internal error, failed preflight, or a refused issue
- 2: escalated, blocked, or `awaiting_owner` (only owner-review waits remain)

A queued command's code goes to `queue-history.jsonl` beside the lock: its own exit code is
lost through `os.execv`. A real run checks its tools first (preflight) and keeps its state in
`_bmad-output/sdlc/runs`.

One run at a time (AD-27): a real `run`, `resume`, `story` or `fix` holds an `fcntl.flock` on
`<repo>/_bmad-output/sdlc/lock`, whatever `--runs-dir` says; a dry-run locks beside its own runs
dir. A command that cannot take it is appended to `queue.jsonl` beside it, under `queue.lock`,
and exits 0; queued entries may not carry `--repo` or `--pipeline`. When a command completes
normally (never after Ctrl-C or a crash), the oldest queued one replaces the process
(`os.execv`), keeping the lock, so queued work runs the code on disk; the lock is released when
the queue is empty. After preflight, these commands check the PRs that `awaiting_owner` runs
wait on: a merged one queues `sdlc resume <run>` ahead of new work (once: a failed automatic
resume is not queued again, and `sdlc status` says so), a closed one escalates its story.
"""

from __future__ import annotations

import argparse
import asyncio
import fcntl
import hashlib
import json
import os
import signal
import sys
import tempfile
from collections.abc import Awaitable, Iterator, Sequence
from contextlib import contextmanager
from pathlib import Path
from typing import Any

from sdlc.adapters.clock import SystemClock
from sdlc.adapters.fake import FixtureBook, fake_ports
from sdlc.adapters.real import preflight, real_ports, telemetry_warnings
from sdlc.app.loop import (
    PAUSE_FILE,
    LoopError,
    Orchestrator,
    format_status,
    write_pause_request,
)
from sdlc.core.models import CoreError, Pipeline, ProposalStatus, RunKind, RunState, Status
from sdlc.core.pipeline import PipelineError, load_pipeline
from sdlc.core.usage import BY, aggregate, format_rows, state_records, totals
from sdlc.ports import AdapterError, Ports
from sdlc.store import LedgerBusy, RunStore, ledger_path, list_runs, load_states, rebuild_ledger


def find_repo_root(start: Path | None = None) -> Path:
    here = (start or Path.cwd()).resolve()
    for p in (here, *here.parents):
        if (p / "sdlc" / "pipeline.yaml").is_file():
            return p
    return Path(__file__).resolve().parents[3]


def _parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="sdlc", description="Zamolxe (Orchestrator)")
    ap.add_argument("--repo", type=Path, help="repo root (default: found from the cwd)")
    ap.add_argument("--runs-dir", type=Path, help="run dirs (default: _bmad-output/sdlc/runs)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    run = sub.add_parser("run", help="start a full run")
    run.add_argument("--from", dest="from_stage", help="start at this stage")
    run.add_argument("--dry-run", action="store_true", help="fake adapters, temporary run dir")
    run.add_argument("--pipeline", type=Path, help="pipeline file (default: sdlc/pipeline.yaml)")
    run.add_argument("--scenario", help=argparse.SUPPRESS)  # a dry-run fixture scenario
    for kind in ("story", "fix"):
        issue = sub.add_parser(kind, help=f"start a {kind} run from an owner's issue (AD-27)")
        issue.add_argument("issue", type=int)
        issue.add_argument("--dry-run", action="store_true", help="fake adapters, temp run dir")
        issue.add_argument("--pipeline", type=Path, help="pipeline file")
        issue.add_argument("--scenario", help=argparse.SUPPRESS)
    st = sub.add_parser("status", help="show a run's state")
    st.add_argument("run_id", nargs="?")
    rs = sub.add_parser("resume", help="resume a crashed or escalated run")
    rs.add_argument("run_id")
    for name, text in (
        ("pause", "pause the running run once its running steps finish (issue #72)"),
        ("stop", "stop the running run now: running steps are interrupted (issue #72)"),
    ):
        ctl = sub.add_parser(name, help=text)
        ctl.add_argument("run_id", nargs="?", help="default: the run holding the lock")
    us = sub.add_parser("usage", help="usage per agent, stage, model, round or run (AD-24)")
    us.add_argument("run_id", nargs="?", help="default: the latest run")
    us.add_argument("--all", action="store_true", help="every run in the runs dir")
    us.add_argument("--by", choices=BY, default="agent")
    us.add_argument("--rebuild", action="store_true", help="regenerate the ledger first")
    return ap


LOCKING = frozenset({"run", "resume", "story", "fix"})
LOCK_ENV = "SDLC_LOCK_FD"  # the lock's fd, handed to the queued command across `os.execv`


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    repo = (args.repo or find_repo_root()).resolve()
    if args.cmd in LOCKING:
        return _locked(args, repo)
    return _dispatch(args, repo)


def _dispatch(args: argparse.Namespace, repo: Path) -> int:
    try:
        if args.cmd == "run":
            return _run(args, repo)
        if args.cmd in ("story", "fix"):
            return _issue_run(args, repo)
        if args.cmd == "status":
            return _status(args, repo)
        if args.cmd == "usage":
            return _usage(args, repo)
        if args.cmd in ("pause", "stop"):
            return _control(args, repo)
        return _resume(args, repo)
    except CoreError as exc:
        print(f"sdlc: cannot continue: {exc}", file=sys.stderr)
        return 1
    except (PipelineError, LoopError, AdapterError, KeyError, OSError) as exc:
        print(f"sdlc: error: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:  # exit code 1 means an internal error
        print(f"sdlc: internal error: {exc!r}", file=sys.stderr)
        return 1


def _runs_dir(args: argparse.Namespace, repo: Path) -> Path:
    return args.runs_dir or repo / "_bmad-output" / "sdlc" / "runs"


# --------------------------------------------------------------------------- lock and queue


class RepoLock:
    """AD-27: `fcntl.flock` on `<dir>/lock`, which records the pid and the run id, and the
    queue `<dir>/queue.jsonl`, changed only under `<dir>/queue.lock`. A lock left by a dead
    process is free: the kernel drops a flock when its holder dies, so it is taken over."""

    def __init__(self, directory: Path) -> None:
        self.dir = directory
        self.path = directory / "lock"
        self.queue_path = directory / "queue.jsonl"
        self.queue_lock = directory / "queue.lock"
        self.history_path = directory / "queue-history.jsonl"
        self.fd: int | None = None
        self.adopted = False  # this process is a drained queue entry
        self.previous: dict[str, Any] | None = None  # what the lock file said before us

    def acquire(self, argv: list[str]) -> bool:
        self.dir.mkdir(parents=True, exist_ok=True)
        if self._adopt():
            self.adopted = True
            self.record(None, argv)
            return True
        if not self._try_flock():
            return False
        self.record(None, argv)
        return True

    def _try_flock(self) -> bool:
        fd = os.open(self.path, os.O_RDWR | os.O_CREAT, 0o644)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            os.close(fd)
            return False
        self.fd = fd
        self.previous = self._info()
        return True

    def history(self, argv: list[str], outcome: int | str) -> None:
        """AD-27: what a drained (or refused) queue entry ended with."""
        line = {"argv": argv, "outcome": outcome, "pid": os.getpid(), "at": _utc()}
        with self.history_path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(line) + "\n")

    def _adopt(self) -> bool:
        """The lock handed over by the process this one replaced (`os.execv`)."""
        value = os.environ.pop(LOCK_ENV, None)
        if not value or not value.isdigit():
            return False
        fd = int(value)
        try:
            same = os.path.samestat(os.fstat(fd), os.stat(self.path))
        except OSError:
            return False
        if not same:
            return False
        os.set_inheritable(fd, False)  # agent processes must never hold the lock
        self.fd = fd
        return True

    def _info(self) -> dict[str, Any] | None:
        try:
            data = json.loads(self.path.read_text(encoding="utf-8") or "null")
        except (OSError, ValueError):
            return None
        return data if isinstance(data, dict) else None

    def record(self, run_id: str | None, argv: list[str] | None = None) -> None:
        if self.fd is None:
            return
        info = self._info() or {}
        info.update(pid=os.getpid(), run_id=run_id)
        if argv is not None:
            info["argv"] = argv
        os.ftruncate(self.fd, 0)
        os.pwrite(self.fd, (json.dumps(info) + "\n").encode("utf-8"), 0)

    def release(self) -> None:
        if self.fd is None:
            return
        os.ftruncate(self.fd, 0)  # a clean release; a non-empty file means its holder died
        fcntl.flock(self.fd, fcntl.LOCK_UN)
        os.close(self.fd)
        self.fd = None

    def hand_over(self) -> None:
        """Keep the lock across `os.execv`: the fd is inherited and named in the environment."""
        assert self.fd is not None
        os.set_inheritable(self.fd, True)
        os.environ[LOCK_ENV] = str(self.fd)

    @contextmanager
    def _queue(self) -> Iterator[list[list[str]]]:
        self.dir.mkdir(parents=True, exist_ok=True)
        fd = os.open(self.queue_lock, os.O_RDWR | os.O_CREAT, 0o644)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX)
            entries: list[list[str]] = []
            if self.queue_path.is_file():
                for line in self.queue_path.read_text(encoding="utf-8").splitlines():
                    try:
                        argv = json.loads(line)["argv"]
                    except (ValueError, KeyError, TypeError):
                        continue  # a torn line
                    if isinstance(argv, list) and all(isinstance(a, str) for a in argv):
                        entries.append(argv)
            before = list(entries)
            yield entries
            if entries != before:
                tmp = self.queue_path.with_suffix(".tmp")
                tmp.write_text("".join(json.dumps({"argv": e}) + "\n" for e in entries))
                os.replace(tmp, self.queue_path)
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)

    def push_or_take(self, argv: list[str]) -> bool:
        """Queue `argv`, then try the lock once more under the queue lock: a holder that
        found the queue empty may have released it meanwhile. True when this process now
        holds the lock (and must drain the queue)."""
        with self._queue() as q:
            q.append(argv)
            return self._try_flock()

    def pop_or_release(self) -> list[str] | None:
        """The oldest entry, or None after releasing the lock, both under the queue lock."""
        with self._queue() as q:
            if q:
                return q.pop(0)
            self.release()
            return None

    def push_front(self, argvs: list[list[str]]) -> None:
        with self._queue() as q:
            rest = [e for e in q if e not in argvs]
            q[:] = [*argvs, *rest]

    def queued(self) -> list[list[str]]:
        with self._queue() as q:
            return list(q)


def _exec(argv: list[str]) -> None:
    """Replace this process (tests patch it)."""
    os.execv(argv[0], argv)


def _utc() -> str:
    return SystemClock().now()


def _replay(args: argparse.Namespace, repo: Path) -> list[str]:
    """The command as a queue entry: an absolute `--runs-dir`, then the subcommand. Never
    `--repo` or `--pipeline`: the draining process runs entries in its own repo."""
    out: list[str] = []
    if args.runs_dir is not None:
        out += ["--runs-dir", str(Path(args.runs_dir).resolve())]
    if args.cmd == "resume":
        return [*out, "resume", args.run_id]
    out.append(args.cmd)
    if args.cmd in ("story", "fix"):
        out.append(str(args.issue))
    if getattr(args, "from_stage", None):
        out += ["--from", args.from_stage]
    if args.dry_run:
        out.append("--dry-run")
    if args.scenario:
        out += ["--scenario", args.scenario]
    return out


AUTO_RESUME = "auto-resume.json"  # in a run dir: the resume the awaited check queued


def _is_dry(args: argparse.Namespace, repo: Path) -> bool:
    if args.cmd != "resume":
        return bool(args.dry_run)
    try:
        raw = json.loads((_runs_dir(args, repo) / args.run_id / "state.json").read_text())
    except (OSError, ValueError):
        return False
    return bool(raw.get("dry_run"))


def _lock_dir(args: argparse.Namespace, repo: Path) -> Path:
    """A real run locks the repo, whatever `--runs-dir` says; a dry-run locks beside its runs
    dir (its own temporary dir by default)."""
    if _is_dry(args, repo):
        return Path(_runs_dir(args, repo)).resolve().parent
    return repo / "_bmad-output" / "sdlc"


def _locked(args: argparse.Namespace, repo: Path) -> int:
    """AD-27: one run at a time; queue when busy; preflight, then settle awaited PRs; drain
    the queue by exec on normal completion only."""
    if args.cmd != "resume" and args.dry_run and args.runs_dir is None:
        args.runs_dir = Path(tempfile.mkdtemp(prefix="sdlc-dryrun-")) / "runs"
    lock = RepoLock(_lock_dir(args, repo))
    me = _replay(args, repo)
    if not lock.acquire(me):
        if getattr(args, "pipeline", None) is not None:
            print("sdlc: the lock is held, and a command with --pipeline is never queued",
                  file=sys.stderr)  # fmt: skip
            return 1
        if not lock.push_or_take(me):
            print(f"sdlc: another sdlc command holds {lock.path}; queued in {lock.queue_path}")
            return 0
        print("sdlc: the lock was released meanwhile; draining the queue")
        _drain(lock, repo)  # runs the oldest entry, maybe this one
        return 0
    if lock.previous and lock.previous.get("pid") not in (None, os.getpid()):
        print(
            f"sdlc: took over the stale lock of process {lock.previous.get('pid')} "
            f"(run {lock.previous.get('run_id') or '-'}), which ended without releasing it"
        )
    args.lock = lock
    try:
        code = _locked_command(args, repo, lock, me)
    except BaseException:  # Ctrl-C or a crash: no queued work runs after it
        if lock.adopted:
            lock.history(me, "interrupted")
        lock.release()
        raise
    if lock.adopted:
        lock.history(me, code)
    if getattr(args, "paused", False):  # issue #72: a pause means "stop working"
        lock.release()
        print("sdlc: the run is paused; the queue is not drained")
        return code
    _drain(lock, repo)
    return code


def _locked_command(args: argparse.Namespace, repo: Path, lock: RepoLock, me: list[str]) -> int:
    if not _is_dry(args, repo):
        ok = _preflight_command(args, repo)
        if ok is None:  # the pipeline does not load: the command reports why
            return _dispatch(args, repo)
        if not ok:
            return 1
        args.preflighted = True
    resumes = _check_awaited(args, repo)
    own = args.cmd == "resume" and args.run_id in resumes
    ahead = [[*_globals(me), "resume", r] for r in resumes if not (own and r == args.run_id)]
    if ahead:
        for entry in ahead:
            _mark_auto_resume(_runs_dir(args, repo) / entry[-1], None)
        lock.push_front([*ahead, me])
        print(f"sdlc: the owner merged awaited PRs; queued sdlc resume "
              f"{', '.join(a[-1] for a in ahead)} ahead of this command")  # fmt: skip
        return 0
    code = _dispatch(args, repo)
    if args.cmd == "resume" and (_runs_dir(args, repo) / args.run_id / AUTO_RESUME).is_file():
        _mark_auto_resume(_runs_dir(args, repo) / args.run_id, code)
    return code


def _mark_auto_resume(run_dir: Path, code: int | None) -> None:
    data = {"queued_at": _utc()} if code is None else {"code": code, "at": _utc()}
    (run_dir / AUTO_RESUME).write_text(json.dumps(data) + "\n", encoding="utf-8")


def _auto_resume_failed(run_dir: Path) -> dict[str, Any] | None:
    """The record of an automatic resume that never completed or exited 1, else None."""
    try:
        data = json.loads((run_dir / AUTO_RESUME).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return data if data.get("code") not in (0, 2) else None


def _preflight_command(args: argparse.Namespace, repo: Path) -> bool | None:
    """A real command's preflight, before anything reads GitHub (AD-27); None when the
    pipeline does not load (nothing reads GitHub then)."""
    path = getattr(args, "pipeline", None) or repo / "sdlc" / "pipeline.yaml"
    if args.cmd == "resume":
        try:
            raw = json.loads((_runs_dir(args, repo) / args.run_id / "state.json").read_text())
            path = Path(raw.get("pipeline_path") or path)
        except (OSError, ValueError):
            pass
    try:
        pipeline, _ = _load(Path(path))
    except (OSError, PipelineError):
        return None
    return _preflight(repo, pipeline)


def _globals(argv: list[str]) -> list[str]:
    """The `--runs-dir` option of a queue entry."""
    out: list[str] = []
    for i, a in enumerate(argv):
        if a == "--runs-dir" and i + 1 < len(argv):
            out += [a, argv[i + 1]]
    return out


def _drain(lock: RepoLock, repo: Path) -> None:
    """Exec the oldest queued command with this repo, or release the lock (AD-27)."""
    while True:
        nxt = lock.pop_or_release()
        if nxt is None:
            return
        if "--repo" in nxt or "--pipeline" in nxt:  # entries never choose their repo or table
            print(f"sdlc: refusing the queued entry {nxt}: it carries --repo or --pipeline",
                  file=sys.stderr)  # fmt: skip
            lock.history(nxt, "refused")
            continue
        print(f"sdlc: running the queued command: sdlc {' '.join(nxt)}", flush=True)
        sys.stderr.flush()
        lock.hand_over()
        _exec([sys.executable, "-m", "sdlc.cli", "--repo", str(repo), *nxt])
        return


def _check_awaited(args: argparse.Namespace, repo: Path) -> list[str]:
    """AD-27: the `awaiting_owner` runs whose awaited PR the owner merged (to resume). A
    closed one escalates its story here. A run whose automatic resume failed is not queued
    again. A failure is reported and skipped: it never blocks new work."""
    runs_dir = _runs_dir(args, repo)
    out: list[str] = []
    for run_id in list_runs(runs_dir):
        try:
            state = RunState.model_validate_json(
                (runs_dir / run_id / "state.json").read_text(encoding="utf-8")
            )
            if state.status is not Status.AWAITING_OWNER or not state.awaiting:
                continue
            own = args.cmd == "resume" and args.run_id == run_id
            if not own and _auto_resume_failed(runs_dir / run_id) is not None:
                continue
            pipeline, _ = _load(Path(state.pipeline_path or repo / "sdlc" / "pipeline.yaml"))
            ports = _ports_for(state, pipeline, repo, runs_dir)
            orch = Orchestrator(pipeline, ports, runs_dir, repo, repo / "sdlc" / "prompts")
            if asyncio.run(_drive(ports, orch.check_awaited(run_id))):
                out.append(run_id)
        except Exception as exc:  # never block new work on a lookup
            print(f"sdlc: warning: cannot check the PRs run {run_id} awaits: {exc}",
                  file=sys.stderr)  # fmt: skip
    return out


def _ports_for(state: RunState, pipeline: Pipeline, repo: Path, runs_dir: Path) -> Ports:
    if state.dry_run:
        book = FixtureBook.from_scenario(state.fixtures or _fixtures(repo), state.scenario)
        return fake_ports(book, str(runs_dir), pipeline.settings)
    s = pipeline.settings
    return real_ports(repo, s.allow_api_key, s.telemetry, s)


def _wire(args: argparse.Namespace, orch: Orchestrator) -> None:
    """Tell the lock the run id, and point the run at its pause request (issue #72)."""
    lock = getattr(args, "lock", None)
    if lock is not None:
        orch.on_run = lock.record
        orch.pause_file = lock.dir / PAUSE_FILE


def _note_paused(args: argparse.Namespace, orch: Orchestrator) -> None:
    if orch.state is not None and orch.state.status is Status.PAUSED:
        args.paused = True


def _alive(pid: Any) -> bool:
    if not isinstance(pid, int) or pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _running(args: argparse.Namespace, repo: Path) -> tuple[Path, dict[str, Any]] | None:
    """The lock dir and lock record of the run in progress: a dry-run's lock beside an
    explicit `--runs-dir` first, then the repo's lock."""
    dirs = [repo / "_bmad-output" / "sdlc"]
    if args.runs_dir is not None:
        dirs.insert(0, Path(args.runs_dir).resolve().parent)
    for d in dict.fromkeys(dirs):
        lock = RepoLock(d)
        info = lock._info()
        if not (info and info.get("run_id") and _alive(info.get("pid"))):
            continue
        if lock.path.is_file() and lock._try_flock():  # nobody holds it: a stale record
            fcntl.flock(lock.fd, fcntl.LOCK_UN)  # type: ignore[arg-type]
            os.close(lock.fd)  # type: ignore[arg-type]
            continue
        return d, info
    return None


def _control(args: argparse.Namespace, repo: Path) -> int:
    """Issue #72: `sdlc pause` writes a request beside the lock and returns at once; `sdlc
    stop` sends SIGTERM to the pid the lock records. Only the run holding the lock."""
    held = _running(args, repo)
    if held is None:
        print("sdlc: no run is in progress", file=sys.stderr)
        return 1
    lock_dir, info = held
    run_id = args.run_id or info["run_id"]
    if run_id != info["run_id"]:
        print(f"sdlc: run {run_id} is not in progress (run {info['run_id']} is)",
              file=sys.stderr)  # fmt: skip
        return 1
    if args.cmd == "pause":
        write_pause_request(lock_dir / PAUSE_FILE, run_id, _utc())
        print(f"sdlc: asked run {run_id} to pause; running steps finish first "
              f"(sdlc status {run_id})")  # fmt: skip
        return 0
    try:
        os.kill(int(info["pid"]), signal.SIGTERM)
    except OSError as exc:
        print(f"sdlc: cannot signal process {info['pid']}: {exc}", file=sys.stderr)
        return 1
    print(f"sdlc: stopping run {run_id} (process {info['pid']})")
    return 0


# Per-stage limits a resume may change; together with `settings` they are left out of the digest.
LIMITS = frozenset(
    {"title", "max_rounds", "timeout_min", "budget_usd", "model", "effort", "max_turns"}
)
# Per-step attribution (AD-24) and limits (AD-7), also left out of the digest.
STEP_EXCLUDED = frozenset({"agent_role", "model", "effort", "max_turns"})


def stage_table_digest(pipeline: Pipeline) -> str:
    """sha256 of the stage table only: stage ids, order, steps, gates, on_fail, entry, per_story.

    Settings (`run_budget_usd`, `max_parallel`, ...) and per-stage and per-step limits are
    excluded, so the owner can fix that config and resume (plan §4.4); a changed stage table
    refuses a resume.
    """
    table = []
    for s in pipeline.stages:
        stage = {k: v for k, v in s.model_dump(mode="json").items() if k not in LIMITS}
        # AD-24: agent_role is attribution, not process; AD-7: step limits are tuning. Neither
        # may block a resume.
        stage["steps"] = [{k: v for k, v in st.items() if k not in STEP_EXCLUDED}
                          for st in stage["steps"]]  # fmt: skip
        table.append(stage)
    blob = json.dumps(table, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()


def _load(path: Path) -> tuple[Pipeline, str]:
    """The pipeline and its stage-table digest, so a resume can refuse a changed stage table."""
    pipeline = load_pipeline(path.read_text(encoding="utf-8"))
    return pipeline, stage_table_digest(pipeline)


def _fixtures(repo: Path) -> Path:
    return repo / "orchestrator" / "tests" / "fixtures" / "dryrun"


def _run(args: argparse.Namespace, repo: Path) -> int:
    pipeline_path = (args.pipeline or repo / "sdlc" / "pipeline.yaml").resolve()
    pipeline, digest = _load(pipeline_path)
    if args.from_stage:
        pipeline.stage(args.from_stage)
    if not args.dry_run:
        return _run_real(args, repo, pipeline, pipeline_path, digest)
    runs_dir = args.runs_dir or Path(tempfile.mkdtemp(prefix="sdlc-dryrun-"))
    fixtures = _fixtures(repo)
    book = FixtureBook.from_scenario(fixtures, args.scenario)
    orch = Orchestrator(
        pipeline,
        fake_ports(book, str(runs_dir), pipeline.settings),
        runs_dir,
        repo,
        repo / "sdlc" / "prompts",
    )
    _wire(args, orch)
    code = asyncio.run(
        orch.start(
            RunKind.FULL,
            dry_run=True,
            from_stage=args.from_stage,
            fixtures=str(fixtures),
            scenario=args.scenario,
            pipeline_path=str(pipeline_path),
            pipeline_sha256=digest,
            stories_reader=book.written_file,
        )
    )
    assert orch.state is not None
    _note_paused(args, orch)
    print()
    print(format_status(orch.state, orch.ports.clock.now()))
    print(f"\nstatus: sdlc --runs-dir {runs_dir} status {orch.state.run_id}")
    return code


def _preflight(repo: Path, pipeline: Pipeline) -> bool:
    on = pipeline.settings.telemetry.enabled
    for warning in telemetry_warnings(repo, telemetry_on=on):
        print(f"sdlc: preflight: warning: {warning}", file=sys.stderr)
    problems = asyncio.run(preflight(repo, allow_api_key=pipeline.settings.allow_api_key))
    for problem in problems:
        print(f"sdlc: preflight: {problem}", file=sys.stderr)
    return not problems


async def _drive(ports: Ports, run: Awaitable[int]) -> int:
    """Await the run, then any notification still in flight."""
    try:
        return await run
    finally:
        await ports.notifier.drain()


def _run_real(
    args: argparse.Namespace, repo: Path, pipeline: Pipeline, pipeline_path: Path, digest: str
) -> int:
    if not getattr(args, "preflighted", False) and not _preflight(repo, pipeline):
        return 1  # before the run dir exists
    runs_dir = _runs_dir(args, repo)
    s = pipeline.settings
    ports = real_ports(repo, s.allow_api_key, s.telemetry, s)
    orch = Orchestrator(pipeline, ports, runs_dir, repo, repo / "sdlc" / "prompts")
    _wire(args, orch)
    code = asyncio.run(
        _drive(
            ports,
            orch.start(
                RunKind.FULL,
                dry_run=False,
                from_stage=args.from_stage,
                pipeline_path=str(pipeline_path),
                pipeline_sha256=digest,
            ),
        )
    )
    assert orch.state is not None
    _note_paused(args, orch)
    print()
    print(format_status(orch.state, ports.clock.now()))
    print(f"\nstatus: sdlc status {orch.state.run_id}")
    return code


def _issue_run(args: argparse.Namespace, repo: Path) -> int:
    """`sdlc story|fix <issue>` (AD-27): an owner's issue starts a run of its one story."""
    kind = RunKind(args.cmd)
    pipeline_path = (args.pipeline or repo / "sdlc" / "pipeline.yaml").resolve()
    pipeline, digest = _load(pipeline_path)
    if args.dry_run:
        runs_dir = args.runs_dir or Path(tempfile.mkdtemp(prefix="sdlc-dryrun-"))
        fixtures = _fixtures(repo)
        book = FixtureBook.from_scenario(fixtures, args.scenario)
        ports = fake_ports(book, str(runs_dir), pipeline.settings)
        extra: dict[str, Any] = {"fixtures": str(fixtures), "scenario": args.scenario}
    else:
        if not getattr(args, "preflighted", False) and not _preflight(repo, pipeline):
            return 1
        runs_dir = _runs_dir(args, repo)
        s = pipeline.settings
        ports = real_ports(repo, s.allow_api_key, s.telemetry, s)
        extra = {}
    orch = Orchestrator(pipeline, ports, runs_dir, repo, repo / "sdlc" / "prompts")
    _wire(args, orch)
    run = orch.start(
        kind,
        dry_run=args.dry_run,
        pipeline_path=str(pipeline_path),
        pipeline_sha256=digest,
        issue=args.issue,
        **extra,
    )
    code = asyncio.run(_drive(ports, run))
    assert orch.state is not None
    _note_paused(args, orch)
    print()
    print(format_status(orch.state, ports.clock.now()))
    where = f"--runs-dir {runs_dir} " if args.dry_run else ""
    print(f"\nstatus: sdlc {where}status {orch.state.run_id}")
    return code


def _status(args: argparse.Namespace, repo: Path) -> int:
    runs_dir = _runs_dir(args, repo)
    run_id = args.run_id or next(reversed(list_runs(runs_dir)), None)
    if run_id is None:
        print(f"sdlc: no runs in {runs_dir}", file=sys.stderr)
        return 1
    store = RunStore(runs_dir / run_id)
    state = store.load(_pipeline_for(store.state_path, repo))  # read-only: no ledger write
    print(format_status(state, SystemClock().now()))
    failed = _auto_resume_failed(runs_dir / run_id)
    if failed is not None and ("code" in failed or state.status is not Status.RUNNING):
        print(
            f"automatic resume after the owner's merge failed "
            f"({'exit ' + str(failed['code']) if 'code' in failed else 'it never completed'}); "
            f"it is not queued again: run sdlc resume {run_id}"
        )
    return 0


def _pipeline_for(state_path: Path, repo: Path) -> Pipeline | None:
    """The pipeline file at the run's recorded `pipeline_path` as it is now (or the repo's
    `sdlc/pipeline.yaml`), for backfilling usage records of a run written before AD-24. The
    roles of such legacy records are best-effort: they come from the current roster, not the
    one the run started with. None when no pipeline loads (records then stay as they are)."""
    try:
        raw = json.loads(state_path.read_text(encoding="utf-8"))
        path = Path(raw.get("pipeline_path") or repo / "sdlc" / "pipeline.yaml")
        if not path.is_file():
            path = repo / "sdlc" / "pipeline.yaml"
        return load_pipeline(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, PipelineError):
        return None


def _usage(args: argparse.Namespace, repo: Path) -> int:
    """AD-24: report usage from each run's `state.json` (backfilled in memory); never writes.
    `--rebuild` regenerates the ledger from every run's state.json first."""
    runs_dir = _runs_dir(args, repo)
    if args.run_id and args.all:
        print("sdlc: give a run id or --all, not both", file=sys.stderr)
        return 1
    runs = list_runs(runs_dir)
    if not runs:
        print(f"sdlc: no runs in {runs_dir}", file=sys.stderr)
        return 1
    if args.run_id and args.run_id not in runs:
        print(f"sdlc: no run {args.run_id} in {runs_dir}", file=sys.stderr)
        return 1
    if args.rebuild:
        try:
            n = rebuild_ledger(runs_dir, lambda path: _pipeline_for(path, repo))
        except LedgerBusy as exc:
            print(f"sdlc: cannot rebuild the usage ledger: {exc}", file=sys.stderr)
            return 1
        print(f"rebuilt {ledger_path(runs_dir)}: {n} records")
    selected = runs if args.all else [args.run_id or runs[-1]]
    states = load_states(runs_dir, selected, lambda path: _pipeline_for(path, repo))
    if not states:
        print(f"sdlc: no readable run in {runs_dir}", file=sys.stderr)
        return 1
    spent = sum(s.budget.spent for s in states)
    records = [r for s in states for r in state_records(s)]
    label = "all runs" if args.all else f"run {selected[0]}"
    print(f"usage of {label} by {args.by}")
    print(format_rows(aggregate(records, args.by), args.by))
    t = totals(records)
    print(
        f"total: {t.attempts} attempts, {t.tokens} tokens, ${t.cost_usd:.2f} "
        f"(budget spent ${spent:.2f})"
    )
    return 0


def _resume(args: argparse.Namespace, repo: Path) -> int:
    runs_dir = _runs_dir(args, repo)
    state = RunStore(runs_dir / args.run_id).load()
    undelivered = any(x.status is ProposalStatus.FAILED for x in state.proposals)
    if state.status is Status.PASSED and not undelivered:  # AD-26: a resume delivers proposals
        print(f"sdlc: run {state.run_id} already passed; nothing to resume", file=sys.stderr)
        return 1
    pipeline_path = Path(state.pipeline_path or repo / "sdlc" / "pipeline.yaml")
    try:
        pipeline, digest = _load(pipeline_path)
    except PipelineError as exc:
        if "agent_role" not in str(exc):
            raise
        print(
            f"sdlc: error: {pipeline_path}: {exc}. Add agent_role to every agent and judge "
            "step, see AD-24 (it is left out of the stage-table digest, so the resume is "
            "not refused for it).",
            file=sys.stderr,
        )
        return 1
    if state.pipeline_sha256 and digest != state.pipeline_sha256:
        print(
            f"sdlc: the stage table in {pipeline_path} changed since run {state.run_id} started "
            f"(sha256 {state.pipeline_sha256[:12]} then, {digest[:12]} now); restore it or "
            "start a new run. Settings and per-stage limits may change.",
            file=sys.stderr,
        )
        return 1
    if state.budget.exhausted and pipeline.settings.run_budget_usd <= state.budget.limit:
        print(
            f"sdlc: run budget exhausted (${state.budget.spent:.2f} spent of "
            f"${pipeline.settings.run_budget_usd:.2f}): raise run_budget_usd in "
            f"sdlc/pipeline.yaml, then sdlc resume {state.run_id}",
            file=sys.stderr,
        )
        return 2
    if state.dry_run:
        book = FixtureBook.from_scenario(state.fixtures or _fixtures(repo), state.scenario)
        ports = fake_ports(book, str(runs_dir), pipeline.settings)
    else:
        if not getattr(args, "preflighted", False) and not _preflight(repo, pipeline):
            return 1
        s = pipeline.settings
        ports = real_ports(repo, s.allow_api_key, s.telemetry, s)
    orch = Orchestrator(pipeline, ports, runs_dir, repo, repo / "sdlc" / "prompts")
    _wire(args, orch)
    code = asyncio.run(_drive(ports, orch.resume(args.run_id)))
    assert orch.state is not None
    _note_paused(args, orch)
    print()
    print(format_status(orch.state, ports.clock.now()))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
