"""Usage ledger per agent and opt-in OpenTelemetry: one test per row of the I/O and edge-case
matrix in spec-orchestrator-usage-ledger.md (AD-24, AD-25, ADR 0019), plus the per-run
invariant that the ledger's cost sum equals `budget.spent`."""

from __future__ import annotations

import json
import re
from pathlib import Path
from urllib.parse import quote

import pytest
from conftest import FIXTURES, PIPELINE_PATH, PROMPTS, REPO, make_dry_run, pipeline_text, run_dry

from sdlc import cli
from sdlc.adapters.claude import CONTENT_LOG_VARS, TELEMETRY_SWITCHES, agent_env, parse_result
from sdlc.adapters.fake import FakeCrash, FixtureBook, fake_ports
from sdlc.adapters.real import telemetry_warnings
from sdlc.app.loop import USAGE_SUMMARY, Orchestrator
from sdlc.core.models import (
    RunKind,
    RunResumed,
    RunStarted,
    Status,
    StepFinished,
    StepResult,
    StepStarted,
    Telemetry,
    UnitStarted,
    WorktreeReady,
)
from sdlc.core.pipeline import PipelineError, load_pipeline
from sdlc.core.state import apply, backfill_usage, initial_state
from sdlc.core.usage import agent_slug, aggregate, state_records
from sdlc.store import RunStore, ledger_path, read_ledger

LEGACY = Path(__file__).resolve().parent / "fixtures" / "legacy" / "state-pre-ad24.json"
T = "2026-11-17T09:00:00Z"


def ledger_cost(runs_dir: Path, run_id: str) -> float:
    return sum(r.cost_usd for r in read_ledger(ledger_path(runs_dir)) if r.run_id == run_id)


def assert_invariant(runs_dir: Path, run_id: str) -> None:
    """AD-24: for each run, the ledger's cost sum equals budget.spent to the cent."""
    spent = RunStore(runs_dir / run_id).load().budget.spent
    assert round(ledger_cost(runs_dir, run_id), 2) == round(spent, 2)
    keys = [r.key for r in read_ledger(ledger_path(runs_dir))]
    assert len(keys) == len(set(keys))


def ledger_lines(runs_dir: Path) -> list[dict]:
    text = ledger_path(runs_dir).read_text(encoding="utf-8")
    return [json.loads(x) for x in text.splitlines() if x.strip()]


# --------------------------------------------------------------------------- per-agent report


def test_usage_per_agent_report(tmp_path, capsys):
    """AC-1: every role that ran is listed, and the total equals the run's spent."""
    runs = tmp_path / "runs"
    base = ["--repo", str(REPO), "--runs-dir", str(runs)]
    assert cli.main([*base, "run", "--dry-run"]) == 0
    run_id = next(p.name for p in runs.iterdir() if p.is_dir())
    capsys.readouterr()
    assert cli.main([*base, "usage", run_id, "--by", "agent"]) == 0
    out = capsys.readouterr().out
    state = RunStore(runs / run_id).load()
    roles = {r.agent_role for r in state_records(state)}
    assert len(roles) == 11  # the ten roles of the roster that ran, plus the judge
    for role in roles:
        assert role in out
    assert f"${state.budget.spent:.2f} (budget spent ${state.budget.spent:.2f})" in out
    rows = aggregate(read_ledger(ledger_path(runs)), "agent")
    assert sum(r.attempts for r in rows) == len(state_records(state))
    assert_invariant(runs, run_id)


# --------------------------------------------------------------------------- subagent tokens


def test_usage_subagent_tokens(tmp_path):
    data = {
        "subtype": "success",
        "total_cost_usd": 0.3,
        "num_turns": 4,
        "duration_ms": 9000,
        "duration_api_ms": 7000,
        "session_id": "s-1",
        "result": "secret result text",
        "usage": {"input_tokens": 10, "output_tokens": 5},
        "modelUsage": {
            "claude-opus": {"inputTokens": 100, "outputTokens": 50, "cacheReadInputTokens": 7,
                            "cacheCreationInputTokens": 3, "costUSD": 0.25},
            "claude-haiku": {"inputTokens": 40, "outputTokens": 20, "cacheReadInputTokens": 1,
                             "cacheCreationInputTokens": 2, "costUSD": 0.05},
        },
    }  # fmt: skip
    result = parse_result(json.dumps(data))
    assert result.usage is not None and result.usage.input_tokens == 10  # for information only
    assert result.raw is not None and "result" not in result.raw
    assert result.raw["modelUsage"] == data["modelUsage"]

    p = load_pipeline(pipeline_text())
    s = running_analysis(p)
    s = apply(p, s, StepFinished(at=T, seq=1, result=result))
    rec = s.step_record(1).usage
    assert rec is not None and rec.usage_complete
    assert (rec.input_tokens, rec.output_tokens) == (140, 70)
    assert (rec.cache_read_input_tokens, rec.cache_creation_input_tokens) == (8, 5)
    assert rec.cost_usd == 0.3 and rec.model == "claude-opus"  # the costlier model
    assert (rec.duration_ms, rec.duration_api_ms, rec.session_id) == (9000, 7000, "s-1")
    assert rec.agent_role == "Pompiliu (Analyst)"
    dumped = rec.model_dump_json()
    assert "secret result text" not in dumped  # no free text in a ledger row


def running_analysis(p):
    s = initial_state(p, run_id="r1", kind=RunKind.FULL, at=T, dry_run=True)
    s = apply(p, s, RunStarted(at=T))
    s = apply(
        p,
        s,
        WorktreeReady(at=T, scope="stage:analysis", branch="b", worktree="/w", head_sha="h"),
    )
    s = apply(p, s, UnitStarted(at=T, scope="stage:analysis", stage="analysis"))
    return apply(
        p,
        s,
        StepStarted(
            at=T, seq=1, scope="stage:analysis", stage="analysis", step="brief", round=1, cap=2
        ),
    )


# --------------------------------------------------------------------------- failed attempts


@pytest.mark.parametrize("scenario", ["gate-fails-then-passes", "usage-limit"])
async def test_usage_failed_and_superseded_attempts(tmp_path, scenario):
    dr = make_dry_run(tmp_path, scenario)
    assert await run_dry(dr) == 0
    measured = [r for r in dr.state.steps if r.agent_role is not None]
    assert measured and all(r.usage is not None for r in measured)
    for r in measured:
        assert r.usage is not None
        assert (r.usage.status, r.usage.outcome) == (r.status, r.outcome)
        assert r.usage.subtype is not None
    rows = {r.key: r for r in aggregate(state_records(dr.state), "round")}
    if scenario == "gate-fails-then-passes":
        assert "story:1-1-ean-input/build r2" in rows  # the retry's cost is visible
        assert rows["story:1-1-ean-input/build r2"].cost_usd > 0
    else:
        dev = [r for r in measured if r.scope == "story:1-1-ean-input" and r.step == "dev"]
        assert [r.usage.status for r in dev if r.usage] == [Status.SUPERSEDED, Status.PASSED]
        first = dev[0].usage
        assert first is not None and first.subtype == "error_during_execution"
        assert first.attempt == 1 and dev[1].usage is not None and dev[1].usage.attempt == 2
        assert rows["story:1-1-ean-input/build r1"].attempts >= 2
    assert_invariant(dr.runs_dir, dr.state.run_id)


# --------------------------------------------------------------------------- timeout or crash


@pytest.mark.parametrize(
    "result",
    [
        StepResult(subtype="error_timeout", is_error=True, total_cost_usd=2.0),
        StepResult(subtype="error_adapter", is_error=True),
        StepResult(
            subtype="error_during_execution",
            is_error=True,
            total_cost_usd=0.0,
            model_usage=parse_result(
                json.dumps(
                    {
                        "subtype": "x",
                        "modelUsage": {"m": {"inputTokens": 9, "outputTokens": 9, "costUSD": 0}},
                    }
                )
            ).model_usage,
        ),
    ],
    ids=["timeout", "unparsable", "error-during-execution-cost-0"],
)
def test_usage_timeout_or_crash(result):
    p = load_pipeline(pipeline_text())
    s = apply(p, running_analysis(p), StepFinished(at=T, seq=1, result=result))
    rec = s.step_record(1).usage
    assert rec is not None and not rec.usage_complete
    assert rec.cost_usd == s.budget.spent == result.total_cost_usd  # what the budget charged
    assert (rec.input_tokens, rec.output_tokens, rec.models) == (0, 0, ())


async def test_usage_crash_interrupt_charges_the_cap(tmp_path):
    dr = make_dry_run(tmp_path, "crash")
    with pytest.raises(FakeCrash):
        await run_dry(dr)
    run_id = dr.state.run_id
    ports = fake_ports(FixtureBook(FIXTURES), str(dr.runs_dir))
    orch = Orchestrator(load_pipeline(pipeline_text()), ports, dr.runs_dir, REPO, PROMPTS, str)
    assert await orch.resume(run_id) == 0
    assert orch.state is not None
    interrupted = [r for r in orch.state.steps if r.interrupted]
    assert interrupted
    for r in interrupted:
        assert r.usage is not None and r.usage.interrupted and not r.usage.usage_complete
        assert r.usage.cost_usd == r.cap and r.usage.input_tokens == 0
    assert_invariant(dr.runs_dir, run_id)


# --------------------------------------------------------------------------- script step


async def test_usage_script_step(tmp_path):
    dr = make_dry_run(tmp_path)
    assert await run_dry(dr) == 0
    scripts = [r for r in dr.state.steps if r.step in ("tests", "fr_ac", "open_pr", "merge_pr")]
    assert scripts and all(r.usage is None and r.agent_role is None for r in scripts)
    ledger_steps = {(r.scope, r.step) for r in read_ledger(ledger_path(dr.runs_dir))}
    assert not any(step == "tests" for _, step in ledger_steps)


# --------------------------------------------------------------------------- crash after save


async def test_usage_crash_between_save_and_append(tmp_path):
    dr = make_dry_run(tmp_path)
    assert await run_dry(dr) == 0
    run_id, runs = dr.state.run_id, dr.runs_dir
    path = ledger_path(runs)
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    # The last append never happened, and the one before it was torn mid-line.
    path.write_text("".join(lines[:-2]) + lines[-2][:25], encoding="utf-8")
    assert len(read_ledger(path)) == len(lines) - 2  # the torn last line is skipped
    RunStore(runs / run_id).load()  # a report's load never writes
    assert len(read_ledger(path)) == len(lines) - 2
    RunStore(runs / run_id).load(reconcile=True)  # the run's own load (resume) reconciles
    assert len(read_ledger(path)) == len(lines)
    assert_invariant(runs, run_id)
    RunStore(runs / run_id).load(reconcile=True)  # appended once only
    assert len(ledger_lines_ok(path)) == len(lines)


def ledger_lines_ok(path: Path) -> list[dict]:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            out.append(json.loads(line))
        except ValueError:
            continue
    return out


# --------------------------------------------------------------------------- resume twice


def test_usage_resume_twice(tmp_path, capsys):
    runs = tmp_path / "runs"
    base = ["--repo", str(REPO), "--runs-dir", str(runs)]
    assert cli.main([*base, "run", "--dry-run", "--scenario", "gate-fails-max-rounds"]) == 2
    run_id = next(p.name for p in runs.iterdir() if p.is_dir())
    assert cli.main([*base, "resume", run_id]) == 2
    assert cli.main([*base, "resume", run_id]) == 2
    keys = [(x["run_id"], x["seq"]) for x in ledger_lines(runs)]
    assert len(keys) == len(set(keys))
    assert_invariant(runs, run_id)


# --------------------------------------------------------------------------- legacy state


def legacy_run(tmp_path: Path) -> tuple[Path, str]:
    runs = tmp_path / "runs"
    raw = json.loads(LEGACY.read_text(encoding="utf-8"))
    raw["pipeline_path"] = str(PIPELINE_PATH)
    run_dir = runs / raw["run_id"]
    for sub in ("steps", "verdicts", "escalations"):
        (run_dir / sub).mkdir(parents=True)
    (run_dir / "state.json").write_text(json.dumps(raw), encoding="utf-8")
    return runs, raw["run_id"]


def test_usage_legacy_state(tmp_path, capsys, monkeypatch):
    """AC-2: the pre-AD-24 snapshot loads, gets backfilled records, and passes the digest."""
    p, digest = cli._load(PIPELINE_PATH)
    runs, run_id = legacy_run(tmp_path)
    state = RunStore(runs / run_id).load(p)
    assert state.pipeline_sha256 == digest  # agent_role is left out of the digest
    done = [r for r in state.steps if r.status is not Status.RUNNING]
    agents = [r for r in done if r.cap > 0]
    assert agents and all(r.usage is not None for r in agents)
    assert all(r.usage is None for r in done if r.cap == 0)  # script steps
    for r in agents:
        assert r.usage is not None
        assert r.usage.cost_usd == r.cost and not r.usage.usage_complete
        assert r.usage.input_tokens == 0 and r.usage.agent_role != "unknown"
    assert state.step_record(26).usage.agent_role == "Pacala (DevOps)"  # type: ignore[union-attr]
    assert backfill_usage(p, state) is state  # idempotent

    # `sdlc resume` is not refused by the digest (it stops later, at the real preflight).
    monkeypatch.setattr(cli, "_preflight", lambda repo, pipeline: False)  # no real gh or git
    capsys.readouterr()
    code = cli.main(["--repo", str(tmp_path), "--runs-dir", str(runs), "resume", run_id])
    assert "stage table" not in capsys.readouterr().err
    assert code == 1


async def test_usage_legacy_state_resumes_with_fakes(tmp_path):
    """The phase 1 run keeps going with the new code: interrupted steps are charged, and the
    ledger matches the budget."""
    runs, run_id = legacy_run(tmp_path)
    ports = fake_ports(FixtureBook(FIXTURES), str(runs))
    orch = Orchestrator(load_pipeline(pipeline_text()), ports, runs, REPO, PROMPTS, str)
    await orch.resume(run_id)
    assert orch.state is not None
    assert all(r.usage is not None for r in orch.state.steps if r.agent_role is not None)
    assert_invariant(runs, run_id)


# --------------------------------------------------------------------------- roles


@pytest.mark.parametrize(
    ("needle", "replacement", "message"),
    [
        (
            '        agent_role: "Pompiliu (Analyst)"\n',
            "",
            "stage 'analysis' step 'brief': an agent step needs an agent_role",
        ),
        (
            '        agent: security-compliance-reviewer\n        agent_role: "Veronel '
            '(Security & Compliance)"\n        prompt: security-prd\n',
            '        agent: security-compliance-reviewer\n        agent_role: "Onofrei (PM)"\n'
            "        prompt: security-prd\n",
            "stage 'design' step 'security_prd': agent_role 'Onofrei (PM)' may not run "
            "'security-compliance-reviewer'",
        ),
        (
            '        agent_role: "Judge (gate check)"\n',
            '        agent_role: "Zamolxe (Orchestrator)"\n',
            "stage 'analysis' step 'coverage': unknown agent_role 'Zamolxe (Orchestrator)'",
        ),
    ],
    ids=["missing", "not-allowed", "unknown"],
)
def test_usage_missing_or_wrong_role(tmp_path, capsys, needle, replacement, message):
    text = pipeline_text()
    assert needle in text
    with pytest.raises(PipelineError) as err:
        load_pipeline(text.replace(needle, replacement, 1))
    assert message in str(err.value)
    path = tmp_path / "pipeline.yaml"
    path.write_text(text.replace(needle, replacement, 1), encoding="utf-8")
    runs = tmp_path / "runs"
    argv = ["--repo", str(REPO), "--runs-dir", str(runs), "run", "--dry-run"]
    assert cli.main([*argv, "--pipeline", str(path)]) == 1  # nothing runs
    assert message in capsys.readouterr().err
    assert not runs.exists()


def test_usage_every_agent_step_has_its_role(pipeline):
    for stage in pipeline.stages:
        for step in stage.steps:
            if step.runner is not None:
                assert step.runner in pipeline.settings.agents[step.agent_role or ""]


# --------------------------------------------------------------------------- warning


def test_usage_warning(tmp_path):
    # Set the budget by pattern, whatever the owner configured (it changes over time).
    text, n = re.subn(r"(?m)^(\s*run_budget_usd:\s*)\S+", r"\g<1>10", pipeline_text())
    assert n == 1
    p = load_pipeline(text)
    runs = tmp_path / "runs"
    ports = fake_ports(FixtureBook(FIXTURES), str(runs))
    lines: list[str] = []
    orch = Orchestrator(p, ports, runs, REPO, PROMPTS, echo=lines.append)
    orch.store = RunStore(runs / "r1")
    orch.store.create()
    orch.state = initial_state(p, run_id="r1", kind=RunKind.FULL, at=T, dry_run=True)
    orch._apply(RunStarted(at=T))
    orch._apply(
        WorktreeReady(at=T, scope="stage:analysis", branch="b", worktree="/w", head_sha="h")
    )
    orch._apply(UnitStarted(at=T, scope="stage:analysis", stage="analysis"))

    def step(seq: int, name: str, cost: float) -> None:
        assert orch.state is not None
        orch._apply(
            StepStarted(at=T, seq=seq, scope="stage:analysis", stage="analysis", step=name,
                        round=1, cap=2)
        )  # fmt: skip
        orch._apply(StepFinished(at=T, seq=seq, result=StepResult(total_cost_usd=cost)))

    step(1, "brief", 7.0)  # at most 7 of 10: below 80%
    assert not any("!! usage:" in x for x in lines)
    step(2, "coverage", 0.5)  # spent 7 + reserved 2 crosses 8 when step 2 starts
    warnings = [e for e in orch.store.events_path.read_text().splitlines() if "usage_warning" in e]
    notes = [n for n in ports.notifier.sent if n[0] == "sdlc usage warning"]  # type: ignore[attr-defined]
    assert len(warnings) == 1 and len(notes) == 1
    assert sum("!! usage:" in x for x in lines) == 1
    step(3, "brief", 0.1)  # still over: no second warning for the same budget
    assert sum("!! usage:" in x for x in lines) == 1

    orch._apply(RunResumed(at=T, budget_limit=20))  # the budget is raised
    assert sum("!! usage:" in x for x in lines) == 1
    step(4, "brief", 9.0)  # 16.6 + 2 >= 16: crosses the new budget's 80%
    assert sum("!! usage:" in x for x in lines) == 2
    assert len([n for n in ports.notifier.sent if n[0] == "sdlc usage warning"]) == 2  # type: ignore[attr-defined]
    assert orch.state is not None and orch.state.usage_warned_limit == 20


# --------------------------------------------------------------------------- retro summary


async def test_usage_retro_summary(tmp_path):
    dr = make_dry_run(tmp_path)
    assert await run_dry(dr) == 0
    retro = next(i for i in dr.ports.claude.invocations if i.key.step == "retrospective")  # type: ignore[attr-defined]
    path = f"{retro.cwd}/{USAGE_SUMMARY}"
    summary = dr.ports.fs.files[path]  # type: ignore[attr-defined]
    assert path in retro.stdin  # the prompt names it
    assert "Marghioala (Developer)" in summary and "## By round" in summary
    assert "story:1-1-ean-input/build r1" in summary
    assert "stage:analysis" not in summary  # only the epic's steps


async def test_usage_learn_branches_revert_the_pipeline(tmp_path):
    dr = make_dry_run(tmp_path)
    assert await run_dry(dr) == 0
    restores = [c for c in dr.ports.git.calls if c[0] == "restore_paths"]  # type: ignore[attr-defined]
    learn = [c for c in restores if "/learn/" in c[1]]
    assert learn and all(c[2] == ("sdlc/**",) for c in learn)


async def test_usage_step_file_has_no_result_text(tmp_path):
    dr = make_dry_run(tmp_path)
    assert await run_dry(dr) == 0
    rec = next(r for r in dr.state.steps if r.step == "brief")
    data = json.loads(
        (dr.runs_dir / dr.state.run_id / f"steps/{rec.seq}-{rec.scope}-{rec.step}.json").read_text()
    )
    assert "result" not in data and "result" not in data["raw"]
    assert "modelUsage" in data["raw"]


# --------------------------------------------------------------------------- rebuild


def test_usage_rebuild(tmp_path, capsys):
    runs = tmp_path / "runs"
    base = ["--repo", str(REPO), "--runs-dir", str(runs)]
    assert cli.main([*base, "run", "--dry-run"]) == 0
    assert cli.main([*base, "run", "--dry-run", "--scenario", "usage-limit"]) == 0
    before = sorted(json.dumps(x, sort_keys=True) for x in ledger_lines(runs))
    ledger_path(runs).unlink()
    capsys.readouterr()
    assert cli.main([*base, "usage", "--rebuild", "--all"]) == 0
    after = sorted(json.dumps(x, sort_keys=True) for x in ledger_lines(runs))
    assert after == before
    assert "rebuilt" in capsys.readouterr().out
    for run_id in [p.name for p in runs.iterdir() if p.is_dir()]:
        assert_invariant(runs, run_id)

    # A corrupt state.json is skipped with a warning, in --rebuild and in --all.
    bad = runs / "20990101-000000-full"
    bad.mkdir()
    (bad / "state.json").write_bytes(b"{not json \xff")
    assert cli.main([*base, "usage", "--rebuild", "--all"]) == 0
    captured = capsys.readouterr()
    assert captured.err.count("skipping run 20990101-000000-full") == 2
    assert sorted(json.dumps(x, sort_keys=True) for x in ledger_lines(runs)) == before


def test_usage_rebuild_refuses_while_a_run_is_running(tmp_path, capsys):
    runs, run_id = legacy_run(tmp_path)  # the phase 1 snapshot: status running
    assert cli.main(["--repo", str(REPO), "--runs-dir", str(runs), "usage", "--rebuild"]) == 1
    err = capsys.readouterr().err
    assert "cannot rebuild the usage ledger" in err and run_id in err
    assert not ledger_path(runs).exists()


def test_usage_reports_never_write_the_ledger(tmp_path, capsys):
    runs, run_id = legacy_run(tmp_path)
    base = ["--repo", str(REPO), "--runs-dir", str(runs)]
    assert cli.main([*base, "status", run_id]) == 0
    assert cli.main([*base, "usage", run_id, "--by", "run"]) == 0
    out = capsys.readouterr().out
    assert not ledger_path(runs).exists()
    state = RunStore(runs / run_id).load()
    assert f"{run_id}" in out and f"${state.budget.spent:.2f}" in out  # backfilled in memory


def test_usage_cli_argument_errors(tmp_path, capsys):
    runs = tmp_path / "runs"
    base = ["--repo", str(REPO), "--runs-dir", str(runs)]
    assert cli.main([*base, "run", "--dry-run"]) == 0
    run_id = next(p.name for p in runs.iterdir() if p.is_dir())
    capsys.readouterr()
    assert cli.main([*base, "usage", run_id, "--all"]) == 1
    assert "not both" in capsys.readouterr().err
    before = ledger_path(runs).read_bytes()
    assert cli.main([*base, "usage", "nope", "--rebuild"]) == 1
    assert "no run nope" in capsys.readouterr().err
    assert ledger_path(runs).read_bytes() == before  # validated before the rebuild
    assert cli.main([*base, "usage", "--by", "run"]) == 0
    out = capsys.readouterr().out
    state = RunStore(runs / run_id).load()
    assert f"{run_id}" in out and f"${state.budget.spent:.2f} (budget spent" in out


def test_usage_status_line(tmp_path, capsys):
    runs = tmp_path / "runs"
    base = ["--repo", str(REPO), "--runs-dir", str(runs)]
    assert cli.main([*base, "run", "--dry-run", "--scenario", "usage-limit"]) == 0
    run_id = next(p.name for p in runs.iterdir() if p.is_dir())
    capsys.readouterr()
    assert cli.main([*base, "status", run_id]) == 0
    out = capsys.readouterr().out
    state = RunStore(runs / run_id).load()
    line = next(x for x in out.splitlines() if x.startswith("usage:"))
    assert "attempts (agents and judges)" in line
    assert f"${state.budget.spent:.2f}" in line
    assert line.endswith("(1 without token counts)")  # the limited attempt


def test_usage_append_failure_does_not_crash_the_run(tmp_path, monkeypatch):
    import sdlc.store as store_mod

    warnings: list[str] = []
    calls = {"n": 0}
    real_append = store_mod._append_lines

    def failing(path, records):
        calls["n"] += 1
        if calls["n"] == 1:
            raise OSError("disk full")
        real_append(path, records)

    monkeypatch.setattr(store_mod, "_append_lines", failing)
    p = load_pipeline(pipeline_text())
    store = RunStore(tmp_path / "runs" / "r1", warn=warnings.append)
    store.create()
    s = apply(
        p, running_analysis(p), StepFinished(at=T, seq=1, result=StepResult(total_cost_usd=1))
    )
    store.save(s)  # the append fails: warned, state.json saved
    assert warnings and "disk full" in warnings[0]
    assert store.load() == s and not ledger_path(tmp_path / "runs").exists()
    store.save(s)  # the next save retries
    assert len(read_ledger(ledger_path(tmp_path / "runs"))) == 1


def test_usage_ledger_read_survives_bad_bytes(tmp_path):
    path = tmp_path / "usage.jsonl"
    path.write_bytes(b"\xff\xfe garbage\n")
    assert read_ledger(path) == ()
    assert read_ledger(tmp_path / "missing" / "usage.jsonl") == ()


# --------------------------------------------------------------------------- dry-run isolation


def test_usage_dry_run_isolation(tmp_path, monkeypatch, capsys):
    real_ledger = REPO / "_bmad-output" / "sdlc" / "runs" / "usage.jsonl"
    before = real_ledger.read_bytes() if real_ledger.exists() else None
    tmp_runs = tmp_path / "tmp-runs"
    monkeypatch.setattr(
        cli.tempfile, "mkdtemp", lambda prefix="": str(tmp_runs.mkdir() or tmp_runs)
    )
    assert cli.main(["--repo", str(REPO), "run", "--dry-run"]) == 0
    # The runs dir is `runs/` in the temporary dir; the lock and queue sit beside it (AD-27).
    assert ledger_path(tmp_runs / "runs").is_file()
    after = real_ledger.read_bytes() if real_ledger.exists() else None
    assert after == before


# --------------------------------------------------------------------------- telemetry


def test_usage_telemetry_off():
    parent = {
        "CLAUDE_CODE_ENABLE_TELEMETRY": "1",
        "OTEL_LOG_USER_PROMPTS": "1",
        "OTEL_METRICS_EXPORTER": "console",
        "OTEL_EXPORTER_OTLP_HEADERS": "x=y",
        "PATH": "/bin",
    }
    env = agent_env(parent, {"SDLC_RUN_ID": "r"}, telemetry=Telemetry())
    assert env == {"PATH": "/bin", "SDLC_RUN_ID": "r"}
    assert not set(env) & {*CONTENT_LOG_VARS, *TELEMETRY_SWITCHES}
    assert agent_env(parent, {}) == {"PATH": "/bin"}  # no telemetry setting means off


def test_usage_telemetry_on(tmp_path):
    t = Telemetry(enabled=True, endpoint="http://localhost:4317", protocol="grpc")
    labels = {
        "run_id": "20261117-174705-full",
        "seq": "7",
        "agent": agent_slug("Marghioala (Developer)"),
        "stage": "build",
        "step": "dev",
        "scope": "story:1-1-a b",
    }
    parent = {
        "OTEL_RESOURCE_ATTRIBUTES": "team=x,sdlc.agent=spoofed",
        "OTEL_LOG_TOOL_DETAILS": "1",
        "OTEL_LOGS_EXPORTER": "console",
        "OTEL_EXPORTER_OTLP_HEADERS": "authorization=abc",
    }
    env = agent_env(parent, {}, telemetry=t, labels=labels)
    assert env["CLAUDE_CODE_ENABLE_TELEMETRY"] == "1"
    assert env["OTEL_METRICS_EXPORTER"] == "otlp"
    assert env["OTEL_EXPORTER_OTLP_ENDPOINT"] == "http://localhost:4317"
    assert env["OTEL_EXPORTER_OTLP_PROTOCOL"] == "grpc"
    assert env["CLAUDE_CODE_OTEL_SHUTDOWN_TIMEOUT_MS"] == "10000"
    assert env["CLAUDE_CODE_OTEL_DIAG_STDERR"] == "1"
    assert env["OTEL_EXPORTER_OTLP_HEADERS"] == "authorization=abc"  # from the runner only
    assert env["OTEL_RESOURCE_ATTRIBUTES"] == (
        "team=x,sdlc.run_id=20261117-174705-full,sdlc.seq=7,sdlc.agent=marghioala_developer,"
        f"sdlc.stage=build,sdlc.step=dev,sdlc.scope={quote('story:1-1-a b', safe='')}"
    )
    assert not set(env) & set(CONTENT_LOG_VARS)
    assert "OTEL_LOGS_EXPORTER" not in env  # console would write into the JSON result

    for exporter in ("console", "prometheus"):
        text = pipeline_text().replace(
            "  telemetry:\n    enabled: false\n",
            f"  telemetry:\n    enabled: true\n    exporter: {exporter}\n"
            "    endpoint: http://localhost:4317\n    protocol: grpc\n",
            1,
        )
        with pytest.raises(PipelineError, match="exporter"):  # no exporter choice: otlp only
            load_pipeline(text)
    on = pipeline_text().replace(
        "  telemetry:\n    enabled: false\n",
        "  telemetry:\n    enabled: true\n    endpoint: http://localhost:4317\n"
        "    protocol: grpc\n",
        1,
    )
    assert load_pipeline(on).settings.telemetry == t
    with pytest.raises(PipelineError, match="endpoint"):
        load_pipeline(on.replace("    endpoint: http://localhost:4317\n", "", 1))


async def test_usage_invocation_labels(tmp_path):
    dr = make_dry_run(tmp_path)
    assert await run_dry(dr) == 0
    dev = next(i for i in dr.ports.claude.invocations if i.key.step == "dev")  # type: ignore[attr-defined]
    assert dev.labels["agent"] == "marghioala_developer"
    assert dev.labels["seq"] == str(dev.key.seq) and dev.labels["scope"] == dev.key.scope


def test_usage_preflight_warns_on_settings_env(tmp_path):
    repo = tmp_path / "repo"
    (repo / ".claude").mkdir(parents=True)
    (repo / ".claude" / "settings.local.json").write_text(
        json.dumps({"env": {"CLAUDE_CODE_ENABLE_TELEMETRY": "1", "OTEL_LOG_USER_PROMPTS": "1"}})
    )
    env = {"CLAUDE_CONFIG_DIR": str(tmp_path / "home")}
    off = telemetry_warnings(repo, environ=env, home=tmp_path, platform="none")
    assert len(off) == 2 and all("AD-25" in w for w in off)
    on = telemetry_warnings(repo, telemetry_on=True, environ=env, home=tmp_path, platform="none")
    assert len(on) == 1 and "OTEL_LOG_USER_PROMPTS" in on[0]


# --------------------------------------------------------------------------- review follow-ups


def test_usage_by_model_totals_equal_spent():
    from sdlc.core.models import ModelUsage, UsageRecord

    def rec(seq, cost, models=(), complete=True):
        return UsageRecord(
            run_id="r", seq=seq, scope="s", stage="a", step="w", round=1, agent_role="X",
            status=Status.PASSED, cost_usd=cost, models=models, usage_complete=complete, at=T,
        )  # fmt: skip

    two = (
        ModelUsage(model="opus-id", input_tokens=10, cost_usd=0.3),
        ModelUsage(model="haiku-id", input_tokens=5, cost_usd=0.1),
    )
    free = (ModelUsage(model="haiku-id", input_tokens=1, cost_usd=0.0),)
    records = [rec(1, 0.8, two), rec(2, 0.5, complete=False), rec(3, 0.2, free)]
    rows = {r.key: r for r in aggregate(records, "model")}
    assert rows["opus-id"].cost_usd == pytest.approx(0.6)  # 0.3 scaled to the 0.8 charged
    assert rows["haiku-id"].cost_usd == pytest.approx(0.2)
    assert rows["haiku-id"].input_tokens == 6
    assert rows["(unattributed)"].cost_usd == pytest.approx(0.7)  # no breakdown, or no cost
    assert sum(r.cost_usd for r in rows.values()) == pytest.approx(1.5)
    for by in ("agent", "stage", "round", "run", "model"):
        assert sum(r.cost_usd for r in aggregate(records, by)) == pytest.approx(1.5)


def test_usage_model_is_the_modelusage_id():
    text = pipeline_text().replace("  - id: analysis\n", "  - id: analysis\n    model: opus\n", 1)
    p = load_pipeline(text)
    result = parse_result(json.dumps({"subtype": "success", "total_cost_usd": 0.2, "modelUsage": {
        "claude-opus-4-1": {"inputTokens": 1, "costUSD": 0.15},
        "claude-haiku-4-5": {"inputTokens": 1, "costUSD": 0.05}}}))  # fmt: skip
    s = apply(p, running_analysis(p), StepFinished(at=T, seq=1, result=result))
    rec = s.step_record(1).usage
    assert rec is not None
    assert (rec.model, rec.model_requested) == ("claude-opus-4-1", "opus")


def test_usage_step_limits_override_the_stage():
    """AD-7, AD-24: the ledger records the step's own model and effort over the stage's."""
    text = pipeline_text().replace(
        "  - id: analysis\n", "  - id: analysis\n    model: opus\n    effort: high\n", 1
    )
    text = text.replace(
        "        prompt: brief\n",
        "        prompt: brief\n        model: haiku\n        effort: low\n",
        1,
    )
    p = load_pipeline(text)
    result = parse_result(json.dumps({"subtype": "success", "total_cost_usd": 0.2, "modelUsage": {
        "claude-haiku-4-5": {"inputTokens": 1, "costUSD": 0.2}}}))  # fmt: skip
    s = apply(p, running_analysis(p), StepFinished(at=T, seq=1, result=result))
    rec = s.step_record(1).usage
    assert rec is not None
    assert (rec.model_requested, rec.effort) == ("haiku", "low")


def test_usage_no_record_when_no_model_ran():
    p = load_pipeline(pipeline_text())
    failed = StepResult(subtype="error_orchestrator", is_error=True, errors=("bad prompt",))
    s = apply(p, running_analysis(p), StepFinished(at=T, seq=1, result=failed))
    assert s.step_record(1).usage is None and s.budget.spent == 0


async def test_usage_raw_stays_in_the_step_file(tmp_path):
    dr = make_dry_run(tmp_path)
    assert await run_dry(dr) == 0
    run_dir = dr.runs_dir / dr.state.run_id
    assert "modelUsage" not in (run_dir / "events.jsonl").read_text()
    assert '"raw"' not in (run_dir / "events.jsonl").read_text()
    assert '"raw"' not in (run_dir / "state.json").read_text()
    files = list((run_dir / "steps").glob("*-brief.json"))
    assert files and "modelUsage" in files[0].read_text()


def test_usage_infinity_does_not_lose_the_cost():
    r = parse_result('{"subtype": "success", "total_cost_usd": 0.4, "num_turns": Infinity,'
                     ' "duration_ms": Infinity}')  # fmt: skip
    assert r.total_cost_usd == 0.4 and r.num_turns == 0 and r.duration_ms is None


def test_usage_judge_with_a_skill_runs_as_judge():
    text = pipeline_text().replace(
        "        kind: judge\n        prompt: brief-coverage\n",
        "        kind: judge\n        skill: bmad-prd\n        prompt: brief-coverage\n",
        1,
    )
    assert load_pipeline(text).stage("analysis").step("coverage").runner == "judge"


def test_usage_settings_validation():
    on = pipeline_text().replace(
        "  telemetry:\n    enabled: false\n",
        "  telemetry:\n    enabled: true\n    endpoint: http://localhost:4317\n"
        "    protocol: grpc\n",
        1,
    )
    with pytest.raises(PipelineError, match="protocol must be one of"):
        load_pipeline(on.replace("protocol: grpc", "protocol: udp", 1))
    with pytest.raises(PipelineError, match="http:// or https://"):
        load_pipeline(on.replace("http://localhost:4317", "localhost:4317", 1))
    with pytest.raises(PipelineError, match="must list what it may run"):
        load_pipeline(pipeline_text().replace(
            '"Pacala (DevOps)": [devops-release]', '"Pacala (DevOps)": []', 1))  # fmt: skip


def test_usage_digest_still_sees_other_step_fields():
    base = cli.stage_table_digest(load_pipeline(pipeline_text()))
    role = (
        pipeline_text()
        .replace('"Loredan (Architect)": [', '"Loredan (Architect)": [bmad-prd, ', 1)
        .replace(
            '        agent_role: "Onofrei (PM)"\n        prompt: prd\n',
            '        agent_role: "Loredan (Architect)"\n        prompt: prd\n',
            1,
        )
    )
    assert load_pipeline(role).stage("requirements").step("prd").agent_role == (
        "Loredan (Architect)"
    )
    assert cli.stage_table_digest(load_pipeline(role)) == base
    changed = pipeline_text().replace("        prompt: prd\n", "        prompt: prd-v2\n", 1)
    assert cli.stage_table_digest(load_pipeline(changed)) != base


def test_usage_backfill_when_the_step_is_gone():
    p = load_pipeline(pipeline_text())
    raw = json.loads(LEGACY.read_text(encoding="utf-8"))
    raw["steps"][0]["step"] = "removed_step"  # seq 1, cap 2.0, no longer in the pipeline
    from sdlc.core.models import RunState

    state = backfill_usage(p, RunState.model_validate(raw))
    rec = state.step_record(1).usage
    assert (
        rec is not None and rec.agent_role == "unknown" and rec.cost_usd == raw["steps"][0]["cost"]
    )
    assert rec.model is None and not rec.usage_complete


async def test_usage_summary_guard_and_epic_scope(tmp_path):
    dr = make_dry_run(tmp_path)
    assert await run_dry(dr) == 0
    from sdlc.core.usage import epic_records, render_summary
    from sdlc.ports import StepKey

    assert any(r.scope == "learn:epic-1" for r in epic_records(dr.state, 1))
    assert f"covers run {dr.state.run_id} only" in render_summary(dr.state, 1)
    # A non-numeric epic subject skips the summary with a log line instead of failing.
    orch = dr.orch
    unit = dr.state.unit("learn:epic-1", "retro")
    assert unit is not None
    d = dr.state.model_dump(mode="json")
    for u in d["units"]:
        if u["scope"] == "learn:epic-1":
            u["subject"] = "epic-x"
    orch.state = type(dr.state).model_validate(d)
    key = StepKey(seq=99, scope="learn:epic-1", stage="retro", step="retrospective", round=1)
    orch._write_usage_summary(key, "/w")
    assert any("no usage summary" in x for x in dr.lines)


async def test_usage_learn_revert_is_recorded(tmp_path):
    dr = make_dry_run(tmp_path)
    dr.ports.claude.book.steps["learn:1-1-ean-input/keeper"] = {  # type: ignore[attr-defined]
        "writes": {"sdlc/pipeline.yaml": "epics.md"}
    }
    assert await run_dry(dr) == 0
    events = [e for e in dr.events() if e["type"] == "paths_reverted"]
    assert len(events) == 1 and events[0]["paths"] == ["sdlc/pipeline.yaml"]
    rec = next(r for r in dr.state.steps if r.seq == events[0]["seq"])
    log = (
        dr.runs_dir / dr.state.run_id / f"steps/{rec.seq}-{rec.scope}-{rec.step}.log"
    ).read_text()
    assert "reverted on this branch" in log and "sdlc/pipeline.yaml" in log
    prompt = (PROMPTS / "knowledge-keeper.md").read_text()
    assert "sdlc/**" in prompt and "recommend" in prompt


def test_usage_telemetry_on_drops_per_signal_overrides():
    t = Telemetry(enabled=True, endpoint="https://collector:4318", protocol="http/protobuf")
    parent = {
        "OTEL_TRACES_EXPORTER": "otlp",
        "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT": "http://elsewhere",
        "OTEL_EXPORTER_OTLP_LOGS_HEADERS": "a=b",
        "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL": "grpc",
    }
    env = agent_env(parent, {}, telemetry=t, labels={"agent": "x"})
    assert not [k for k in env if k in parent]
    assert env["OTEL_EXPORTER_OTLP_ENDPOINT"] == "https://collector:4318"


async def test_usage_telemetry_on_reaches_the_cli(tmp_path, monkeypatch):
    from test_limits import KEY, install_claude_stub

    from sdlc.adapters.claude import ClaudeCli, build_argv
    from sdlc.ports import Invocation

    log = install_claude_stub(tmp_path, monkeypatch)
    monkeypatch.setenv("OTEL_LOG_USER_PROMPTS", "1")
    t = Telemetry(enabled=True, endpoint="http://localhost:4317", protocol="grpc")
    argv = build_argv(budget_usd=1, allowed_tools=("Read",), restrict_tools=True)
    labels = {"run_id": "r", "seq": "1", "agent": "marghioala_developer"}
    inv = Invocation(key=KEY, argv=argv, stdin="p", cwd=str(tmp_path), labels=labels)
    assert (await ClaudeCli(telemetry=t).run(inv)).subtype == "success"
    env = json.loads(log.read_text("utf-8").splitlines()[0])["env"]
    assert env["OTEL_METRICS_EXPORTER"] == "otlp"
    assert "sdlc.agent=marghioala_developer" in env["OTEL_RESOURCE_ATTRIBUTES"]
    assert "OTEL_LOG_USER_PROMPTS" not in env


def test_usage_preflight_warning_values(tmp_path):
    repo = tmp_path / "repo"
    (repo / ".claude").mkdir(parents=True)
    settings = repo / ".claude" / "settings.local.json"
    env = {"CLAUDE_CONFIG_DIR": str(tmp_path / "home")}
    settings.write_text(json.dumps({"env": {"CLAUDE_CODE_ENABLE_TELEMETRY": "0",
                                            "OTEL_METRICS_EXPORTER": "false"}}))  # fmt: skip
    assert telemetry_warnings(repo, environ=env, home=tmp_path, platform="none") == []
    settings.write_text(json.dumps({"env": {"OTEL_LOGS_EXPORTER": "console",
                                            "OTEL_METRICS_EXPORTER": "otlp"}}))  # fmt: skip
    on = telemetry_warnings(repo, telemetry_on=True, environ=env, home=tmp_path, platform="none")
    assert len(on) == 1 and "OTEL_LOGS_EXPORTER" in on[0] and "console" in on[0]


def test_usage_cli_prints_the_preflight_warning(tmp_path, monkeypatch, capsys):
    repo = tmp_path / "repo"
    (repo / ".claude").mkdir(parents=True)
    (repo / ".claude" / "settings.local.json").write_text(
        json.dumps({"env": {"OTEL_LOG_USER_PROMPTS": "1"}})
    )
    monkeypatch.setenv("CLAUDE_CONFIG_DIR", str(tmp_path / "home"))

    async def no_tools(*args, **kwargs):  # no real gh or git call
        return ["stopped by the test"]

    monkeypatch.setattr(cli, "preflight", no_tools)
    assert cli.main(["--repo", str(repo), "run", "--pipeline", str(PIPELINE_PATH)]) == 1
    err = capsys.readouterr().err
    assert "sdlc: preflight: warning: OTEL_LOG_USER_PROMPTS" in err


def test_usage_resume_names_the_agent_role_fix(tmp_path, capsys):
    runs, run_id = legacy_run(tmp_path)
    custom = tmp_path / "custom.yaml"
    custom.write_text(
        pipeline_text().replace('        agent_role: "Pompiliu (Analyst)"\n', "", 1), "utf-8"
    )
    state_path = runs / run_id / "state.json"
    raw = json.loads(state_path.read_text())
    raw["pipeline_path"] = str(custom)
    state_path.write_text(json.dumps(raw))
    assert cli.main(["--repo", str(REPO), "--runs-dir", str(runs), "resume", run_id]) == 1
    assert "Add agent_role to every agent and judge step, see AD-24" in capsys.readouterr().err
