"""Issue #72: `sdlc pause`, `sdlc stop` and resuming a paused run."""

from __future__ import annotations

import asyncio
import dataclasses
import fcntl
import json
import os
import signal
from pathlib import Path

import pytest
from conftest import REPO, make_dry_run, run_dry

from sdlc import cli
from sdlc.app.loop import (
    OWNER_STOP,
    PAUSE_FILE,
    format_status,
    read_pause_request,
    write_pause_request,
)
from sdlc.core.models import Status, StepKind

T = "2026-11-18T12:00:00Z"


class Hooked:
    """Wraps the fake `claude` port: `hook` runs on the first agent step, before it runs."""

    def __init__(self, inner, hook) -> None:
        self.inner = inner
        self.hook = hook
        self.calls = 0

    async def run(self, inv):
        self.calls += 1
        if self.calls == 1:
            await self.hook()
        return await self.inner.run(inv)

    def __getattr__(self, name):
        return getattr(self.inner, name)


def hooked(tmp_path: Path, make_hook):
    dr = make_dry_run(tmp_path)
    dr.orch.pause_file = tmp_path / PAUSE_FILE
    dr.orch.poll_s = 0.005
    claude = Hooked(dr.ports.claude, make_hook(dr))
    dr.orch.ports = dataclasses.replace(dr.ports, claude=claude)
    dr.ports = dr.orch.ports
    return dr


async def test_pause_lets_the_running_step_finish(tmp_path):
    """AC-1, AC-4: no new step after the request; the running one is recorded."""
    seen: dict[str, str] = {}

    def make_hook(dr):
        async def hook():
            write_pause_request(dr.orch.pause_file, dr.state.run_id, T)
            for _ in range(200):  # until the loop has seen the request
                if dr.state.pause_requested_at is not None:
                    break
                await asyncio.sleep(0.005)
            seen["status"] = format_status(dr.state)

        return hook

    dr = hooked(tmp_path, make_hook)
    code = await run_dry(dr)
    assert code == 0
    s = dr.state
    assert s.status is Status.PAUSED and s.pause_kind == "pause" and s.paused_at
    assert s.exit_code == 0
    assert "pausing (1 steps finishing)" in seen["status"]
    assert f"paused at {s.paused_at} (pause)" in format_status(s)
    assert [r.status for r in s.steps] == [Status.PASSED]  # finished, nothing new started
    assert not s.steps[0].interrupted
    assert s.budget.spent < s.steps[0].cap  # its real cost, not the cap
    kinds = [e["type"] for e in dr.events()]
    assert kinds.index("pause_requested") < kinds.index("step_finished") < kinds.index("run_paused")
    assert "run_stopped" not in kinds
    assert not dr.orch.pause_file.exists()  # the request is consumed


async def test_resume_continues_a_paused_run(tmp_path):
    """AC-3, AC-5: work finished during the pause is kept; a leftover request is removed."""

    def make_hook(dr):
        async def hook():
            write_pause_request(dr.orch.pause_file, dr.state.run_id, T)
            await asyncio.sleep(0.05)

        return hook

    dr = hooked(tmp_path, make_hook)
    assert await run_dry(dr) == 0
    run_id, first = dr.state.run_id, dr.state.steps[0]
    write_pause_request(tmp_path / PAUSE_FILE, run_id, T)  # a leftover request

    again = make_dry_run(tmp_path)
    again.orch.pause_file = tmp_path / PAUSE_FILE
    code = await again.orch.resume(run_id)
    assert code == 0
    s = again.state
    assert s.status is Status.PASSED and s.pause_kind is None and s.paused_at is None
    assert not (tmp_path / PAUSE_FILE).exists()
    assert s.step_record(first.seq).status is Status.PASSED
    same = [r for r in s.steps if (r.scope, r.step, r.round) == (first.scope, first.step, 1)]
    assert len(same) == 1  # not redone
    assert not any(r.interrupted for r in s.steps)


async def test_stop_interrupts_running_steps(tmp_path):
    """AC-2: the step is interrupted, its worktree reset and its cap charged."""
    resets: list[tuple[str, str]] = []

    def make_hook(dr):
        async def hook():
            dr.orch.request_stop()
            await asyncio.Event().wait()  # hangs until cancelled

        return hook

    dr = hooked(tmp_path, make_hook)
    git = dr.ports.git
    real_reset = git.reset_hard

    async def reset_hard(worktree, sha):
        resets.append((worktree, sha))
        return await real_reset(worktree, sha)

    object.__setattr__(git, "reset_hard", reset_hard)  # also on a frozen dataclass
    code = await run_dry(dr)
    assert code == 0
    s = dr.state
    assert s.status is Status.PAUSED and s.pause_kind == "stop"
    rec = s.steps[0]
    assert rec.status is Status.FAILED and rec.interrupted
    assert rec.cost == rec.cap and s.budget.spent == rec.cap and s.budget.reserved == 0
    scope = s.scope(rec.scope)
    assert scope is not None and (scope.worktree, scope.head_sha) in resets
    events = dr.events()
    stopped = [e for e in events if e["type"] == "run_stopped"]
    assert [e["reason"] for e in stopped] == [OWNER_STOP]
    assert events[-1]["type"] == "run_paused" and events[-1]["kind"] == "stop"
    assert "(stop)" in format_status(s)


async def test_pause_cancels_script_waits_at_no_cost(tmp_path):
    """A waiting script step is interrupted without cost when a pause arrives."""
    dr = make_dry_run(tmp_path)
    dr.orch.pause_file = tmp_path / PAUSE_FILE
    dr.orch.poll_s = 0.005
    orch = dr.orch
    real = orch._run_script

    async def slow_script(key, stage, step, worktree):
        write_pause_request(orch.pause_file, dr.state.run_id, T)
        await asyncio.Event().wait()
        return await real(key, stage, step, worktree)

    orch._run_script = slow_script  # type: ignore[method-assign]
    code = await run_dry(dr)
    assert code == 0 and dr.state.status is Status.PAUSED
    scripts = [
        r for r in dr.state.steps if orch.p.stage(r.stage).step(r.step).kind is StepKind.SCRIPT
    ]
    assert scripts and all(r.interrupted and r.cost == 0 for r in scripts)
    assert not any(r.status is Status.RUNNING for r in dr.state.steps)


async def test_a_request_for_another_run_is_ignored(tmp_path):
    """AC-5."""
    dr = make_dry_run(tmp_path)
    dr.orch.pause_file = tmp_path / PAUSE_FILE
    write_pause_request(dr.orch.pause_file, "another-run", T)
    code = await run_dry(dr)
    assert code == 0 and dr.state.status is Status.PASSED
    assert read_pause_request(dr.orch.pause_file) == {"run_id": "another-run", "at": T}


# --------------------------------------------------------------------------- CLI


def lock_record(lock_dir: Path, run_id: str | None, pid: int) -> int:
    """A held lock (an open fd with the flock) that records `run_id` and `pid`."""
    lock_dir.mkdir(parents=True, exist_ok=True)
    (lock_dir / "lock").write_text(json.dumps({"pid": pid, "run_id": run_id}) + "\n")
    fd = os.open(lock_dir / "lock", os.O_RDWR)
    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    return fd


def test_cli_ignores_a_stale_lock_record(tmp_path, capsys):
    """A record nobody holds (its process died) is never signalled."""
    (tmp_path / "lock").write_text(json.dumps({"pid": os.getpid(), "run_id": "run-a"}) + "\n")
    runs = ["--repo", str(REPO), "--runs-dir", str(tmp_path / "runs")]
    assert cli.main([*runs, "stop"]) == 1
    assert "no run is in progress" in capsys.readouterr().err


def test_cli_pause_writes_the_request(tmp_path, capsys):
    fd = lock_record(tmp_path, "run-a", os.getpid())
    runs = ["--repo", str(REPO), "--runs-dir", str(tmp_path / "runs")]
    assert cli.main([*runs, "pause", "run-b"]) == 1
    assert not (tmp_path / PAUSE_FILE).exists()
    assert cli.main([*runs, "pause"]) == 0
    assert read_pause_request(tmp_path / PAUSE_FILE)["run_id"] == "run-a"
    os.close(fd)


def test_cli_stop_signals_the_lock_holder(tmp_path, monkeypatch):
    fd = lock_record(tmp_path, "run-a", 4242)
    sent: list[tuple[int, int]] = []
    monkeypatch.setattr(cli.os, "kill", lambda pid, sig: sent.append((pid, sig)))
    runs = ["--repo", str(REPO), "--runs-dir", str(tmp_path / "runs")]
    assert cli.main([*runs, "stop"]) == 0
    assert (4242, signal.SIGTERM) in sent
    os.close(fd)


def test_cli_without_a_running_run(tmp_path, monkeypatch):
    lock_record(tmp_path, None, os.getpid())  # held, but no run started yet
    monkeypatch.setattr(cli, "_running", lambda args, repo: None)
    runs = ["--repo", str(REPO), "--runs-dir", str(tmp_path / "runs")]
    assert cli.main([*runs, "pause"]) == 1
    assert cli.main([*runs, "stop"]) == 1


def test_cli_a_paused_run_does_not_drain_the_queue(tmp_path, monkeypatch, capsys):
    """AC-1, AC-2: the lock is released and the queued entry stays."""
    runs_dir = tmp_path / "runs"
    queued = ["--runs-dir", str(runs_dir), "run", "--dry-run"]
    (tmp_path / "queue.jsonl").write_text(json.dumps(queued) + "\n")
    real_wire = cli._wire

    def wire(args, orch):
        real_wire(args, orch)
        orch.request_stop()

    monkeypatch.setattr(cli, "_wire", wire)
    monkeypatch.setattr(cli, "_exec", lambda argv: pytest.fail(f"drained: {argv}"))
    code = cli.main(["--repo", str(REPO), "--runs-dir", str(runs_dir), "run", "--dry-run"])
    assert code == 0
    assert "the queue is not drained" in capsys.readouterr().out
    assert json.loads((tmp_path / "queue.jsonl").read_text().splitlines()[0]) == queued
    assert (tmp_path / "lock").read_text() == ""  # released cleanly
