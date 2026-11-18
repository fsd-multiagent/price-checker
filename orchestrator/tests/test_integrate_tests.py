"""Issue #87: integrate runs the tests gate after the rebase, and a red CI's failed-job log
reaches the Developer fix round (AD-15)."""

from __future__ import annotations

import json

from conftest import FIXTURES, PROMPTS, REPO, make_dry_run, pipeline_text, run_dry

from sdlc.adapters.fake import FixtureBook, fake_ports
from sdlc.app.loop import Orchestrator
from sdlc.core import gates as core_gates
from sdlc.core.models import Status
from sdlc.core.pipeline import load_pipeline

SCOPE = "story:1-1-ean-input"
CI_LOG = (
    "backend\tcheck\t2026-11-18T10:00:00Z /w/src/main/java/A.java:3: error: cannot find symbol\n"
    "backend\tcheck\t2026-11-18T10:00:00Z [command]/usr/bin/git fetch origin\n"
    "backend\tcheck\t2026-11-18T10:00:00Z   GITHUB_TOKEN: ***\n"
)


def _orch(tmp_path, steps: dict) -> tuple[Orchestrator, object]:
    book = FixtureBook(FIXTURES, {"steps": steps})
    runs = tmp_path / "runs"
    pipeline = load_pipeline(pipeline_text())
    ports = fake_ports(book, str(runs), pipeline.settings)
    return Orchestrator(pipeline, ports, runs, REPO, PROMPTS, echo=lambda _: None), ports


def _ci_green_verdict(orch: Orchestrator, round_: int) -> dict:
    assert orch.store is not None
    text = orch.store.read_artifact(f"verdicts/{SCOPE}/r{round_}/ci_green.json")
    assert text is not None
    return json.loads(text)


def _dev_prompt(ports, round_: int) -> str:
    return next(
        inv.stdin
        for inv in ports.claude.invocations
        if inv.key.scope == SCOPE and inv.key.step == "dev" and inv.key.round == round_
    )


async def test_rebase_tests_red_fails_before_open_pr(tmp_path):
    """AC-1, AC-3: `--scenario rebase-tests-red` stops in integrate before any PR."""
    dr = make_dry_run(tmp_path, "rebase-tests-red")
    assert await run_dry(dr) == 2
    integrate = dr.unit(SCOPE, "integrate")
    assert integrate.status is Status.ESCALATED and integrate.escalated_gate == "ci_green"
    assert dr.unit(SCOPE, "build").round == 2  # one Developer fix round (AD-15)
    steps = {r.step for r in dr.state.steps if r.scope == SCOPE and r.stage == "integrate"}
    assert "tests_rebased" in steps and "open_pr" not in steps
    assert not [c for c in dr.ports.gh.calls if c[0] == "ensure_pr" and "1-1-ean-input" in c[1]]
    verdict = _ci_green_verdict(dr.orch, 1)
    summaries = " ".join(f["summary"] for f in verdict["findings"])
    assert "tests_rebased" in summaries and "fake tests: exit 1" in summaries
    assert dr.unit("story:1-2-retailer-config", "integrate").status is Status.PASSED


async def test_red_ci_excerpt_reaches_the_fix_round(tmp_path):
    """AC-2: the excerpt is in the ci_green finding and in the next dev prompt's feedback."""
    orch, ports = _orch(tmp_path, {f"{SCOPE}/ci_green/r1": {"exit_code": 1, "ci_log": CI_LOG}})
    assert await orch.start(dry_run=True, fixtures=str(FIXTURES)) == 0
    assert orch.state.unit(SCOPE, "build").round == 2
    summary = next(
        f["summary"] for f in _ci_green_verdict(orch, 1)["findings"] if "ci_green" in f["id"]
    )
    assert "checks not green" in summary
    assert "A.java:3: error: cannot find symbol" in summary
    assert "git fetch" not in summary and "TOKEN" not in summary
    prompt = _dev_prompt(ports, 2)
    assert "A.java:3: error: cannot find symbol" in prompt


async def test_failed_log_fetch_keeps_ci_red(tmp_path):
    """A failing log fetch is swallowed: the output is `checks not green`, no excerpt."""
    orch, ports = _orch(tmp_path, {f"{SCOPE}/ci_green/r1": {"exit_code": 1, "ci_log_error": True}})
    assert await orch.start(dry_run=True, fixtures=str(FIXTURES)) == 0
    assert ("failed_ci_log", ports.gh.prs[orch.state.scope(SCOPE).branch]) in ports.gh.calls
    rec = next(r for r in orch.state.steps if r.scope == SCOPE and r.step == "ci_green")
    assert rec.round == 1 and rec.outcome == "error"
    summary = next(
        f["summary"] for f in _ci_green_verdict(orch, 1)["findings"] if "ci_green" in f["id"]
    )
    assert summary.rstrip().endswith("checks not green")


def test_failed_step_output_tail_is_clipped():
    pipeline = load_pipeline(pipeline_text())
    stage = pipeline.stage("integrate")
    from sdlc.core.models import Outcome, StepRecord

    rec = StepRecord(
        seq=7, scope=SCOPE, stage="integrate", step="rebase", round=1, status=Status.FAILED,
        cap=0, started_at="t", outcome=Outcome.ERROR, reason="exit code 1",
    )  # fmt: skip
    out = "x" * 10_000 + "END"
    sv = core_gates.evaluate_stage(stage, [rec], {}, "t", {7: out})
    finding = next(f for v in sv.verdicts for f in v[1].findings if f.id == "rebase-outcome")
    assert finding.summary.endswith("END") and "[...]" in finding.summary
    assert len(finding.summary) < core_gates.OUTPUT_TAIL + 200


async def test_gh_failed_ci_log_reads_only_failed_runs_on_the_head(tmp_path):
    from sdlc.adapters.gh import GhCli
    from sdlc.adapters.proc import ProcResult

    gh = GhCli(tmp_path)
    viewed: list[str] = []

    async def fake_json(*args, check=True):
        if args[:2] == ("pr", "view"):
            return {"headRefOid": "abc"}
        assert args[:4] == ("run", "list", "--commit", "abc")
        return [
            {"databaseId": 1, "headSha": "abc", "conclusion": "failure"},
            {"databaseId": 2, "headSha": "old", "conclusion": "failure"},
            {"databaseId": 3, "headSha": "abc", "conclusion": "success"},
        ]

    async def fake_gh(*args, check=True):
        viewed.append(args[2])
        return ProcResult(args, 0, f"log {args[2]}", "")

    gh._json = fake_json  # type: ignore[method-assign]
    gh._gh = fake_gh  # type: ignore[method-assign]
    assert await gh.failed_ci_log(5) == "log 1"
    assert viewed == ["1"]
