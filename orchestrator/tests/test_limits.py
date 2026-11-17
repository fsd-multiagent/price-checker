"""Subscription limits, budget wait and credential preflight: one test per row of the I/O and
edge-case matrix in spec-orchestrator-subscription-limits.md (AD-1, AD-7, AD-8, AD-11, AD-18,
AD-23, ADR 0005)."""

from __future__ import annotations

import dataclasses
import json
import os
import sys
import time
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from conftest import FIXTURES, PROMPTS, REPO, make_dry_run, pipeline_text, run_dry

from sdlc import cli
from sdlc.adapters import real
from sdlc.adapters.claude import ClaudeCli, agent_env, build_argv, flag_usage_limit, parse_reset
from sdlc.adapters.clock import SystemClock
from sdlc.adapters.fake import FakeClock, FakeCrash, FixtureBook, fake_ports
from sdlc.adapters.real import credential_problems, preflight
from sdlc.app.loop import Orchestrator, format_status
from sdlc.core.models import (
    BudgetExhausted,
    CoreError,
    EscalationOpened,
    ExhaustBudget,
    Finish,
    GateEvaluated,
    OpenEscalation,
    Outcome,
    RunKind,
    RunResumed,
    RunStarted,
    StartStep,
    Status,
    Step,
    StepFinished,
    StepKind,
    StepResult,
    StepStarted,
    Tagged,
    TickInputs,
    UnitStarted,
    UsageLimited,
    Wait,
    WorktreeReady,
)
from sdlc.core.outcome import derive_outcome
from sdlc.core.pipeline import PipelineError, load_pipeline
from sdlc.core.scheduler import decide
from sdlc.core.state import _move, apply, initial_state
from sdlc.ports import Invocation, Ports, StepKey

T = "2026-11-17T09:00:00Z"
LIMIT_TEXT = "You've hit your session limit"
STUBS = Path(__file__).resolve().parent / "stubs"
KEY = StepKey(seq=1, scope="stage:a", stage="a", step="w", round=1)


# --------------------------------------------------------------------------- helpers


def parallel_pipeline(caps: dict[str, float], limit: float, settings: str = ""):
    """Run-level stages with no dependencies, so they all start in the same tick."""
    stages = "".join(
        f"""
  - id: {sid}
    entry: [full]
    needs: []
    budget_usd: {cap}
    steps:
      - {{id: w, kind: agent, agent_role: T, skill: s, prompt: p, tools: [Read], writes: true}}
    gate: review_verdicts
"""
        for sid, cap in caps.items()
    )
    return load_pipeline(
        f"settings:\n  run_budget_usd: {limit}\n  agents: {{T: [s, x, worker, judge]}}\n"
        f"{settings}stages:{stages}"
    )


def running(pipeline, spent: float = 0.0):
    """Every stage's unit is running with its worktree ready; nothing has started a step."""
    s = initial_state(pipeline, run_id="r1", kind=RunKind.FULL, at=T, dry_run=True)
    s = apply(pipeline, s, RunStarted(at=T))
    for stage in pipeline.stages:
        scope = f"stage:{stage.id}"
        s = apply(
            pipeline,
            s,
            WorktreeReady(at=T, scope=scope, branch="b", worktree=f"/{scope}", head_sha="h"),
        )
        s = apply(pipeline, s, UnitStarted(at=T, scope=scope, stage=stage.id))
    if spent:
        s = s.model_validate(
            {**s.model_dump(), "budget": {**s.budget.model_dump(), "spent": spent}}
        )
    return s


def start(pipeline, s, actions):
    for a in actions:
        assert isinstance(a, StartStep)
        s = apply(
            pipeline,
            s,
            StepStarted(
                at=T,
                seq=s.next_seq,
                scope=a.scope,
                stage=a.stage,
                step=a.step,
                round=a.round,
                cap=a.cap,
            ),
        )
    return s


def seq_of(s, stage: str) -> int:
    return next(r.seq for r in s.steps if r.stage == stage and r.status is Status.RUNNING)


def starts(actions) -> list[str]:
    return [a.stage for a in actions if isinstance(a, StartStep)]


LIMITED = StepResult(
    subtype="error_during_execution",
    is_error=True,
    errors=(LIMIT_TEXT,),
    usage_limited=True,
    total_cost_usd=0.1,
)
DONE = StepResult(result='```json\n{"status": "done"}\n```')


def install_claude_stub(tmp_path: Path, monkeypatch) -> Path:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    exe = bin_dir / "claude"
    exe.write_text(f"#!{sys.executable}\n" + (STUBS / "claude_stub.py").read_text("utf-8"))
    exe.chmod(0o755)
    log = tmp_path / "calls.jsonl"
    monkeypatch.setenv("PATH", f"{bin_dir}{os.pathsep}{os.environ['PATH']}")
    monkeypatch.setenv("STUB_LOG", str(log))
    return log


# --------------------------------------------------------------------------- AD-11: budget


def test_limits_budget_wait():
    p = parallel_pipeline({"a": 5, "b": 5, "c": 15}, limit=20)
    s = running(p)
    actions = decide(p, s, TickInputs())
    assert starts(actions) == ["a", "b"]  # c waits for a reservation to be released
    assert not any(isinstance(a, ExhaustBudget) for a in actions)
    s = start(p, s, actions)
    assert s.budget.reserved == 10
    assert decide(p, s, TickInputs()) == ()  # waiting on running steps; no Finish
    s = apply(p, s, StepFinished(at=T, seq=seq_of(s, "a"), result=DONE))
    assert s.budget.reserved == 5
    assert starts(decide(p, s, TickInputs())) == ["c"]
    assert s.escalations == ()


def test_limits_budget_fifo():
    p = parallel_pipeline({"x": 10, "big": 15, "small": 5}, limit=20)
    s = running(p)
    actions = decide(p, s, TickInputs())
    # big waits; small would fit (10 + 5) but is queued after big, so it does not start.
    assert starts(actions) == ["x"]
    s = start(p, s, actions)
    assert starts(decide(p, s, TickInputs())) == []
    s = apply(p, s, StepFinished(at=T, seq=seq_of(s, "x"), result=DONE))
    assert starts(decide(p, s, TickInputs())) == ["big", "small"]


def test_limits_budget_exhausted():
    p = parallel_pipeline({"a": 5}, limit=20)
    s = running(p, spent=18)
    actions = decide(p, s, TickInputs())
    assert [type(a) for a in actions] == [ExhaustBudget]
    s = apply(p, s, BudgetExhausted(at=T, reason=actions[0].reason))
    assert s.escalation("run") is not None
    s = apply(p, s, EscalationOpened(at=T, scope="run", issue=7))
    assert decide(p, s, TickInputs()) == (Finish(exit_code=2),)


def test_limits_reserved_is_the_sum_of_running_caps():
    p = parallel_pipeline({"a": 5, "b": 5}, limit=20)
    s = start(p, running(p), decide(p, running(p), TickInputs()))
    assert s.budget.reserved == 10
    s = apply(p, s, StepFinished(at=T, seq=seq_of(s, "a"), result=LIMITED))
    assert s.budget.reserved == 5 and s.budget.spent == pytest.approx(0.1)


# --------------------------------------------------------------------------- AD-18: limits


async def test_limits_limited(tmp_path):
    dr = make_dry_run(tmp_path, "usage-limit")
    assert await run_dry(dr) == 0
    state = dr.state
    scope = "story:1-1-ean-input"
    dev = [r for r in state.steps if r.scope == scope and r.step == "dev"]
    assert [(r.round, r.status) for r in dev] == [(1, Status.SUPERSEDED), (1, Status.PASSED)]
    assert dev[0].outcome is Outcome.LIMITED
    assert dr.unit(scope, "build").round == 1  # no round counted
    assert state.counter(scope, "review_verdicts") == 0
    assert state.escalations == ()
    # AD-6: the worktree was reset to the scope head and the discarded diff saved.
    wt = state.scope(scope).worktree
    assert ("reset_hard", wt, dev[0].base_sha) in dr.ports.git.calls
    assert (dr.runs_dir / state.run_id / "steps" / f"{dev[0].seq}.discarded.patch").exists()
    # The run paused once until resume_at (>= the limit + usage_limit_wait_min), then went on.
    clock = dr.ports.clock
    assert isinstance(clock, FakeClock)
    assert clock.sleeps == [state.resume_at]
    assert state.resume_at is not None and state.resume_at >= "2026-11-16T09:30:00Z"
    assert dev[1].started_at >= state.resume_at
    assert state.limit_episode_start is None  # the next done step ended the episode
    assert sum("paused until" in line for line in dr.lines) == 1
    paused = [m for m in dr.ports.notifier.sent if m[0] == "sdlc paused"]  # type: ignore[attr-defined]
    assert paused == [("sdlc paused", f"plan usage limit: until {state.resume_at}")]
    # The cut-short attempt's diff is not fed to the next attempt as feedback.
    invs = [i for i in dr.ports.claude.invocations if i.key.scope == scope and i.key.step == "dev"]  # type: ignore[attr-defined]
    assert [i.attempt for i in invs] == [1, 2]
    assert "None: this is the first round." in invs[1].stdin
    assert "discarded.patch" not in invs[1].stdin


def test_limits_limited_in_parallel():
    p = parallel_pipeline({"a": 1, "b": 1}, limit=20)
    s = start(p, running(p), decide(p, running(p), TickInputs()))
    sa, sb = seq_of(s, "a"), seq_of(s, "b")
    s = apply(p, s, StepFinished(at=T, seq=sa, result=LIMITED))
    s = apply(p, s, StepFinished(at=T, seq=sb, result=LIMITED))
    s = apply(p, s, UsageLimited(at=T, seq=sa, resets_at="2026-11-17T11:00:00Z"))
    s = apply(p, s, UsageLimited(at="2026-11-17T09:00:01Z", seq=sb, resets_at=None))
    assert s.resume_at == "2026-11-17T11:00:00Z"  # the max of both
    assert s.limit_episode_start == T  # one episode, counted once
    assert all(r.status is Status.SUPERSEDED for r in s.steps)


def test_limits_limit_text_on_success():
    now = T
    quoted = StepResult(result='I read "hit your weekly limit" in the docs.\n{"status": "done"}')
    assert not flag_usage_limit(quoted, now).usage_limited
    step = Step(id="w", kind=StepKind.AGENT, skill="s", prompt="p", tools=("Read",))
    assert derive_outcome(step, flag_usage_limit(quoted, now), None).outcome is Outcome.DONE
    # The same text on an error result is a limit, in `result` or in `errors`.
    for text in (
        "You've hit your weekly limit",
        "You've hit your Opus 4 limit",
        "Hit your monthly spend limit",
        "Claude AI usage limit reached|1763391600",
        "5-hour limit reached ∙ resets 3pm (UTC)",
    ):
        err = StepResult(subtype="error_during_execution", is_error=True, errors=(text,))
        assert flag_usage_limit(err, now).usage_limited, text
    assert not flag_usage_limit(StepResult(subtype="error_x", errors=("boom",)), now).usage_limited


def test_limits_reset_time_parsing():
    now = T  # 2026-11-17 09:00 UTC
    assert parse_reset("hit your session limit · resets 3pm (UTC)", now) == "2026-11-17T15:00:00Z"
    assert parse_reset("resets 8am (UTC)", now) == "2026-11-18T08:00:00Z"  # tomorrow
    assert parse_reset("resets 2:30pm (Europe/Bucharest)", now) == "2026-11-17T12:30:00Z"
    assert parse_reset("resets 12am (UTC)", now) == "2026-11-18T00:00:00Z"
    assert parse_reset("resets 15:30 (UTC)", now) == "2026-11-17T15:30:00Z"
    assert parse_reset("resets Nov 20, 3pm (UTC)", now) == "2026-11-20T15:00:00Z"  # dated
    assert parse_reset("resets Jan 2 at 5am (UTC)", now) == "2027-01-02T05:00:00Z"  # next year
    assert parse_reset("usage limit reached|1763391600", now) == "2025-11-17T15:00:00Z"
    assert parse_reset("resets 3pm (Mars/Olympus)", now) is None  # unknown zone
    assert parse_reset("resets 3pm", now) is None  # no zone
    assert parse_reset("resets 3 (UTC)", now) is None  # no am/pm and no minutes
    assert parse_reset("no reset time", now) is None


async def test_limits_limit_episode_too_long(tmp_path):
    dr = make_dry_run(tmp_path, "usage-limit-forever")
    assert await run_dry(dr) == 2
    state = dr.state
    assert state.status is Status.ESCALATED and state.limit_exceeded
    run_esc = state.escalation("run")
    assert run_esc is not None and "plan usage limit" in run_esc.reason
    assert run_esc.issue is not None
    dev = [r for r in state.steps if r.scope == "story:1-1-ean-input" and r.step == "dev"]
    assert all(r.status is Status.SUPERSEDED for r in dev)
    assert dr.unit("story:1-1-ean-input", "build").round == 1


class CrashingClock(FakeClock):
    async def sleep_until(self, until: str) -> None:
        raise FakeCrash("simulated kill while waiting")


async def test_limits_crash_while_waiting(tmp_path):
    runs = tmp_path / "runs"
    ports = fake_ports(FixtureBook.from_scenario(FIXTURES, "usage-limit"), str(runs))
    ports = Ports(**{**ports.__dict__, "clock": CrashingClock()})
    orch = Orchestrator(load_pipeline(pipeline_text()), ports, runs, REPO, PROMPTS, echo=str)
    with pytest.raises(FakeCrash):
        await orch.start(RunKind.FULL, dry_run=True, fixtures=str(FIXTURES))
    assert orch.store is not None
    on_disk = orch.store.load()
    assert on_disk.resume_at is not None and on_disk.status is Status.RUNNING

    # The same scenario: the fake reads the attempt from the state, so attempt 1 is not replayed.
    fresh = fake_ports(FixtureBook.from_scenario(FIXTURES, "usage-limit"), str(runs))
    again = Orchestrator(load_pipeline(pipeline_text()), fresh, runs, REPO, PROMPTS, echo=str)
    assert await again.resume(on_disk.run_id) == 0
    assert isinstance(fresh.clock, FakeClock) and fresh.clock.sleeps == []  # no wait
    assert again.state is not None and again.state.resume_at is None
    dev = [r for r in again.state.steps if r.scope == "story:1-1-ean-input" and r.step == "dev"]
    assert dev[-1].status is Status.PASSED


def test_limits_resume_clears_the_wait():
    p = parallel_pipeline({"a": 1}, limit=20)
    s = running(p)
    s = s.model_validate(
        {
            **s.model_dump(),
            "status": "escalated",
            "resume_at": "2026-11-18T00:00:00Z",
            "limit_episode_start": T,
            "limit_exceeded": True,
        }
    )
    s = apply(p, s, RunResumed(at=T))
    assert (s.resume_at, s.limit_episode_start, s.limit_exceeded) == (None, None, False)


def test_limits_waiting_is_not_finished():
    p = parallel_pipeline({"a": 1}, limit=20)
    s = running(p)
    s = s.model_validate({**s.model_dump(), "resume_at": "2026-11-17T10:00:00Z"})
    assert decide(p, s, TickInputs(now="2026-11-17T09:30:00Z")) == (
        Wait(until="2026-11-17T10:00:00Z"),
    )
    assert starts(decide(p, s, TickInputs(now="2026-11-17T10:00:01Z"))) == ["a"]


# --------------------------------------------------------------------------- AD-23: credentials


def isolate_credentials(tmp_path: Path, monkeypatch) -> Path:
    """No developer settings, keys or managed settings reach the credential check."""
    home = tmp_path / "home"
    home.mkdir()
    monkeypatch.setenv("HOME", str(home))
    for name in (*real.CREDENTIAL_VARS, "CLAUDE_CONFIG_DIR"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setattr(real, "MANAGED_SETTINGS", {})
    return home


def test_limits_api_key_present(tmp_path, monkeypatch, capsys):
    home = isolate_credentials(tmp_path, monkeypatch)
    repo = tmp_path / "repo"
    (repo / ".claude").mkdir(parents=True)
    assert credential_problems(repo, {"ANTHROPIC_API_KEY": ""}, home) == []  # empty is unset
    (found,) = credential_problems(repo, {"ANTHROPIC_API_KEY": "x"}, home)
    assert "ANTHROPIC_API_KEY" in found and "bill per token" in found
    (repo / ".claude" / "settings.local.json").write_text(
        json.dumps({"apiKeyHelper": "/bin/key"}), "utf-8"
    )
    (found,) = credential_problems(repo, {}, home)
    assert "apiKeyHelper" in found and "settings.local.json" in found
    (home / ".claude").mkdir()
    (home / ".claude" / "settings.json").write_text(
        json.dumps({"env": {"CLAUDE_CODE_USE_BEDROCK": "1"}}), "utf-8"
    )
    assert len(credential_problems(repo, {}, home)) == 2
    # CLAUDE_CONFIG_DIR moves the user settings; 0 and false leave a provider switch off.
    conf = tmp_path / "conf"
    conf.mkdir()
    (conf / "settings.json").write_text(json.dumps({"env": {"ANTHROPIC_AUTH_TOKEN": "t"}}), "utf-8")
    found = credential_problems(repo, {"CLAUDE_CONFIG_DIR": str(conf)}, home)
    assert len(found) == 2 and any("ANTHROPIC_AUTH_TOKEN" in f for f in found)
    bare = tmp_path / "bare-home"
    for off in ("0", "false", "FALSE", ""):
        assert credential_problems(tmp_path, {"CLAUDE_CODE_USE_VERTEX": off}, bare) == []
    assert credential_problems(tmp_path, {"CLAUDE_CODE_USE_VERTEX": "1"}, bare)

    # The CLI: a real run exits 1 naming the source, before its run dir exists.
    monkeypatch.setenv("ANTHROPIC_API_KEY", "x")
    runs = tmp_path / "runs"
    assert cli.main(["--repo", str(REPO), "--runs-dir", str(runs), "run"]) == 1
    assert "ANTHROPIC_API_KEY is set in the environment" in capsys.readouterr().err
    assert not runs.exists()


def test_limits_managed_settings_are_read(tmp_path, monkeypatch):
    home = isolate_credentials(tmp_path, monkeypatch)
    managed = tmp_path / "managed-settings.json"
    managed.write_text(json.dumps({"apiKeyHelper": "/bin/key"}), "utf-8")
    monkeypatch.setattr(real, "MANAGED_SETTINGS", {"darwin": managed, "linux": managed})
    (found,) = credential_problems(tmp_path, {}, home, platform="darwin")
    assert str(managed) in found
    assert credential_problems(tmp_path, {}, home, platform="win32") == []
    assert real.settings_files(tmp_path, home, {}, "linux")[-1] == managed


def test_limits_cli_allow_api_key(tmp_path, monkeypatch, capsys):
    isolate_credentials(tmp_path, monkeypatch)
    monkeypatch.setenv("ANTHROPIC_API_KEY", "x")
    empty = tmp_path / "empty-bin"
    empty.mkdir()
    monkeypatch.setenv("PATH", str(empty))  # preflight still fails on tools, before any call
    pipe = tmp_path / "pipeline.yaml"
    pipe.write_text(
        pipeline_text().replace("  allow_api_key: false\n", "  allow_api_key: true\n", 1), "utf-8"
    )
    runs = tmp_path / "runs"
    assert (
        cli.main(["--repo", str(REPO), "--runs-dir", str(runs), "run", "--pipeline", str(pipe)])
        == 1
    )
    err = capsys.readouterr().err
    assert "claude not found on PATH" in err and "AD-23" not in err


async def test_limits_api_key_allowed(tmp_path, monkeypatch):
    home = isolate_credentials(tmp_path, monkeypatch)
    env = {"ANTHROPIC_API_KEY": "x"}
    refused = await preflight(tmp_path, environ=env, home=home)
    assert any("ANTHROPIC_API_KEY" in p for p in refused)
    allowed = await preflight(tmp_path, allow_api_key=True, environ=env, home=home)
    assert not any("AD-23" in p for p in allowed)
    text = pipeline_text().replace("  allow_api_key: false\n", "  allow_api_key: true\n", 1)
    assert load_pipeline(text).settings.allow_api_key


# --------------------------------------------------------------------------- AD-8: the agent


async def test_limits_env_stripping(tmp_path, monkeypatch):
    log = install_claude_stub(tmp_path, monkeypatch)
    monkeypatch.setenv("CLAUDE_CODE_RETRY_WATCHDOG", "1")
    monkeypatch.setenv("CLAUDE_CODE_RETRY_WATCHDOG_USAGE_LIMIT_WAIT", "1")
    monkeypatch.setenv("CLAUDE_CODE_EFFORT_LEVEL", "high")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "x")
    argv = build_argv(budget_usd=1, allowed_tools=("Read",), restrict_tools=True)
    # The invocation's own env cannot bring a stripped name back.
    extra = {"SDLC_X": "1", "CLAUDE_CODE_EFFORT_LEVEL": "max", "CLAUDE_CODE_RETRY_WATCHDOG": "1"}
    inv = Invocation(key=KEY, argv=argv, stdin="p", cwd=str(tmp_path), env=extra)
    result = await ClaudeCli().run(inv)
    assert result.subtype == "success"
    assert await ClaudeCli(allow_api_key=True).run(inv)
    first, allowed = [json.loads(x) for x in log.read_text("utf-8").splitlines()]
    assert first["env"]["SDLC_X"] == "1"
    assert not any(k.startswith("CLAUDE_CODE_RETRY_WATCHDOG") for k in first["env"])
    assert "CLAUDE_CODE_EFFORT_LEVEL" not in first["env"]
    assert "ANTHROPIC_API_KEY" not in first["env"]  # AD-23: no credential reaches the agent
    assert allowed["env"]["ANTHROPIC_API_KEY"] == "x"  # unless allow_api_key
    assert agent_env({"A": "1", "CLAUDE_CODE_EFFORT_LEVEL": "x"}, {"B": "2"}) == {
        "A": "1",
        "B": "2",
    }


async def test_limits_model_effort_turns(tmp_path):
    text = pipeline_text().replace("  default_effort: null\n", "  default_effort: medium\n", 1)
    text = text.replace(
        "  - id: analysis\n",
        "  - id: analysis\n    model: sonnet\n    effort: low\n    max_turns: 20\n",
        1,
    )
    dr = make_dry_run(tmp_path, pipeline=load_pipeline(text))
    assert await run_dry(dr) == 0
    invocations = dr.ports.claude.invocations  # type: ignore[attr-defined]
    brief = next(i for i in invocations if i.key.step == "brief")
    assert brief.argv[-6:] == ("--model", "sonnet", "--effort", "low", "--max-turns", "20")
    prd = next(i for i in invocations if i.key.step == "prd")
    assert prd.argv[-2:] == ("--effort", "medium") and "--max-turns" not in prd.argv
    bad = text.replace("effort: low", "effort: ultracode", 1)
    with pytest.raises(PipelineError, match="effort"):
        load_pipeline(bad)
    with pytest.raises(PipelineError, match="max_turns must be greater than 0"):
        load_pipeline(text.replace("max_turns: 20", "max_turns: 0", 1))


class StubCwdClaude(ClaudeCli):
    """The real adapter, run in an existing directory (the fake worktrees exist only in memory)."""

    def __init__(self, cwd: Path) -> None:
        super().__init__()
        self.cwd = str(cwd)

    async def run(self, inv: Invocation) -> StepResult:
        return await super().run(dataclasses.replace(inv, cwd=self.cwd))


ONE_STAGE = """\
settings:
  run_budget_usd: 5
  agents: {T: [s, x, worker, stub-worker, judge]}
stages:
  - id: only
    entry: [full]
    budget_usd: 1
    steps:
      - {id: work, kind: agent, agent_role: T, agent: worker, prompt: work, tools: [Read],
         writes: true}
      - {id: tests, kind: script, script: tests}
    gate: {kind: tests, steps: [tests]}
"""


async def test_limits_unparseable_output(tmp_path, monkeypatch):
    log = install_claude_stub(tmp_path, monkeypatch)
    monkeypatch.setenv("STUB_CLAUDE_MODE", "garbage")
    prompts = tmp_path / "prompts"
    prompts.mkdir()
    (prompts / "work.md").write_text("Do the work in ${worktree}.\n", "utf-8")
    runs = tmp_path / "runs"
    fake = fake_ports(FixtureBook(FIXTURES), str(runs))
    ports = Ports(**{**fake.__dict__, "claude": StubCwdClaude(tmp_path)})
    lines: list[str] = []
    orch = Orchestrator(load_pipeline(ONE_STAGE), ports, runs, REPO, prompts, echo=lines.append)
    assert await orch.start(RunKind.FULL, dry_run=True, fixtures=str(FIXTURES)) == 2
    assert orch.state is not None
    work = [r for r in orch.state.steps if r.step == "work"]
    assert len(work) == 3  # one failed round each, up to max_rounds; the run went on
    assert all(r.status is Status.FAILED and r.outcome is Outcome.ERROR for r in work)
    assert "error_adapter" in (work[0].reason or "")
    assert len(log.read_text("utf-8").splitlines()) == 3


def test_limits_resume_after_an_effort_change(tmp_path, capsys):
    runs = tmp_path / "runs"
    pipe = tmp_path / "pipeline.yaml"
    pipe.write_text(pipeline_text(), encoding="utf-8")
    base = ["--repo", str(REPO), "--runs-dir", str(runs)]
    assert cli.main([*base, "run", "--dry-run", "--pipeline", str(pipe), "--scenario",
                     "gate-fails-max-rounds"]) == 2  # fmt: skip
    run_id = next(p.name for p in runs.iterdir() if p.is_dir())
    changed = pipeline_text().replace(
        "  - id: build\n",
        "  - id: build\n    effort: high\n    max_turns: 40\n    model: opus\n",
        1,
    )
    pipe.write_text(changed, "utf-8")
    capsys.readouterr()
    assert cli.main([*base, "resume", run_id]) == 2  # the scenario still fails; not refused
    assert "stage table" not in capsys.readouterr().err
    assert cli.stage_table_digest(load_pipeline(changed)) == cli.stage_table_digest(
        load_pipeline(pipeline_text())
    )


# --------------------------------------------------------------------------- AC-1


def test_limits_ac1_usage_limit_dry_run(tmp_path, capsys):
    runs = tmp_path / "runs"
    argv = ["--repo", str(REPO), "--runs-dir", str(runs), "run", "--dry-run"]
    assert cli.main([*argv, "--scenario", "usage-limit"]) == 0
    out = capsys.readouterr().out
    assert out.count("paused until") == 1 and "status=passed" in out
    assert "escalation:" not in out


async def test_limits_limit_text_from_the_cli(tmp_path, monkeypatch):
    """A plain-text limit message (exit 1, not JSON) still ends the attempt `limited`."""
    install_claude_stub(tmp_path, monkeypatch)
    monkeypatch.setenv("STUB_CLAUDE_MODE", "limit-text")
    prompts = tmp_path / "prompts"
    prompts.mkdir()
    (prompts / "work.md").write_text("Do the work in ${worktree}.\n", "utf-8")
    runs = tmp_path / "runs"
    fake = fake_ports(FixtureBook(FIXTURES), str(runs))
    ports = Ports(**{**fake.__dict__, "claude": StubCwdClaude(tmp_path)})
    orch = Orchestrator(load_pipeline(ONE_STAGE), ports, runs, REPO, prompts, echo=str)
    assert await orch.start(RunKind.FULL, dry_run=True, fixtures=str(FIXTURES)) in (0, 2)
    assert orch.state is not None
    first = next(r for r in orch.state.steps if r.step == "work")
    assert first.status is Status.SUPERSEDED and first.outcome is Outcome.LIMITED
    assert orch.state.limit_episode_start is not None
    assert (orch.state.resume_at or "") >= "2026-11-16T15:00:00Z"


def test_limits_superseded_is_for_step_records_only():
    with pytest.raises(CoreError, match="step records only"):
        _move({"status": "running", "scope": "stage:a"}, Status.SUPERSEDED, "unit stage:a/a")
    with pytest.raises(CoreError):
        _move({"status": "running"}, Status.SUPERSEDED, "run")


def test_limits_second_run_escalation_is_reported_again():
    p = parallel_pipeline({"a": 5}, limit=20)
    s = running(p, spent=18)
    s = apply(p, s, BudgetExhausted(at=T, reason="first"))
    s = apply(p, s, EscalationOpened(at=T, scope="run", issue=7))
    s = apply(p, s, RunResumed(at=T))
    s = apply(p, s, BudgetExhausted(at=T, reason="second"))
    esc = s.escalation("run")
    assert esc is not None and esc.issue is None
    assert "first" in esc.reason and "second" in esc.reason
    assert OpenEscalation(scope="run", reason=esc.reason) in decide(p, s, TickInputs())


def test_limits_step_finished_alone_sets_the_wait():
    """A crash between StepFinished and UsageLimited keeps the wait; UsageLimited is idempotent."""
    p = parallel_pipeline({"a": 1, "b": 1}, limit=20)
    s = start(p, running(p), decide(p, running(p), TickInputs()))
    sa, sb = seq_of(s, "a"), seq_of(s, "b")
    s = apply(p, s, StepFinished(at="2026-11-17T09:00:05Z", seq=sa, result=LIMITED))
    assert s.resume_at == "2026-11-17T09:30:05Z" and s.limit_episode_start == "2026-11-17T09:00:05Z"
    again = apply(p, s, UsageLimited(at="2026-11-17T09:00:09Z", seq=sa))
    again = apply(p, again, UsageLimited(at="2026-11-17T09:00:12Z", seq=sa))
    assert (again.resume_at, again.limit_episode_start) == (s.resume_at, s.limit_episode_start)
    # b started before the limit: its `done` does not close the episode.
    s = apply(p, s, StepFinished(at="2026-11-17T09:00:06Z", seq=sb, result=DONE))
    assert s.limit_episode_start == "2026-11-17T09:00:05Z"


def test_limits_episode_closes_on_a_step_started_after_it():
    p = parallel_pipeline({"a": 1}, limit=20)
    s = start(p, running(p), decide(p, running(p), TickInputs()))
    s = apply(p, s, StepFinished(at="2026-11-17T09:00:05Z", seq=seq_of(s, "a"), result=LIMITED))
    s = s.model_validate(
        {
            **s.model_dump(),
            "scopes": [{**x, "needs_reset": False} for x in s.model_dump()["scopes"]],
        }
    )
    (action,) = decide(p, s, TickInputs(now="2026-11-17T09:31:00Z"))
    s = apply(
        p,
        s,
        StepStarted(
            at="2026-11-17T09:31:00Z", seq=s.next_seq, scope=action.scope, stage=action.stage,
            step=action.step, round=action.round, cap=action.cap,
        ),
    )  # fmt: skip
    s = apply(p, s, StepFinished(at="2026-11-17T09:40:00Z", seq=seq_of(s, "a"), result=DONE))
    assert s.limit_episode_start is None


def test_limits_budget_wait_and_limit_wait_in_one_tick():
    p = parallel_pipeline({"a": 10, "b": 15}, limit=20)
    s = running(p)
    s = start(p, s, [a for a in decide(p, s, TickInputs()) if a.stage == "a"])
    s = s.model_validate({**s.model_dump(), "resume_at": "2026-11-17T10:00:00Z"})
    now = TickInputs(now="2026-11-17T09:30:00Z")
    assert decide(p, s, now) == ()  # b waits for both; no escalation, no Finish, no Wait yet
    s = apply(p, s, StepFinished(at=T, seq=seq_of(s, "a"), result=DONE))
    s = apply(p, s, GateEvaluated(at=T, scope="stage:a", stage="a", round=1, verdicts=()))
    s = apply(p, s, Tagged(at=T, scope="stage:a", stage="a", tag="t"))
    assert decide(p, s, now) == (Wait(until="2026-11-17T10:00:00Z"),)
    assert starts(decide(p, s, TickInputs(now="2026-11-17T10:00:01Z"))) == ["b"]


def test_limits_status_during_a_pause_and_after_an_escalated_episode():
    p = parallel_pipeline({"a": 1}, limit=20)
    s = running(p)
    paused = s.model_validate(
        {**s.model_dump(), "resume_at": "2026-11-17T10:00:00Z", "limit_episode_start": T}
    )
    assert "plan usage limit: resume_at 2026-11-17T10:00:00Z" in format_status(
        paused, "2026-11-17T09:30:00Z"
    )
    assert "plan usage limit" not in format_status(paused, "2026-11-17T10:00:01Z")
    escalated = paused.model_validate({**paused.model_dump(), "limit_exceeded": True})
    out = format_status(escalated, "2026-11-18T00:00:00Z")
    assert "plan usage limit" in out and "exceeded usage_limit_max_wait_h" in out


async def test_limits_system_clock_sleep_until():
    clock = SystemClock()
    start_at = time.monotonic()
    await clock.sleep_until("2000-01-01T00:00:00Z")  # in the past: returns at once
    assert time.monotonic() - start_at < 0.5
    target = (datetime.now(UTC) + timedelta(seconds=2)).strftime("%Y-%m-%dT%H:%M:%SZ")
    await clock.sleep_until(target)
    assert clock.now() >= target
