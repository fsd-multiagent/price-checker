"""core/gates.py: the AD-19 registry and AD-10 normalisation."""

from __future__ import annotations

import json

import pytest

from sdlc.core import gates
from sdlc.core.gates import GATE_KINDS, cites_rule, evaluate_stage, normalise, parse_verdict_text
from sdlc.core.models import Outcome, Status, StepRecord, Verdict

EPICS_OK = "```yaml\nkey: 1-1-a\ntitle: A\ndepends_on: []\ntouches: []\nsources: []\n```\n"


def rec(step, outcome=Outcome.DONE, verdict=None, status_value=None):
    return StepRecord(
        seq=1,
        scope="s",
        stage="x",
        step=step,
        round=1,
        status=Status.PASSED if outcome is Outcome.DONE else Status.FAILED,
        cap=0,
        started_at="t",
        outcome=outcome,
        verdict=verdict,
        status_value=status_value,
    )


def v(*findings, verdict="pass"):
    return normalise(
        {"reviewer": "r", "target": "t", "verdict": verdict, "findings": list(findings)}
    )


BLOCKER = {"id": "B-1", "severity": "blocker", "rule": "S-1", "summary": "unapproved fetch"}


def test_registry_has_exactly_the_ad19_kinds():
    assert {
        "judge_coverage",
        "prd_validate_and_fr_ac",
        "review_verdicts",
        "readiness",
        "terraform_plan",
        "tests",
        "ci_green",
        "docs_updated",
    } == GATE_KINDS


@pytest.mark.parametrize(
    ("rule", "ok"),
    [("C-7", True), ("S-13", True), ("C-B2", True), ("AC-3", True), ("FR-2-AC1", True),
     ("", False), (None, False), ("style", False), ("AD-15", True), ("AD-x", False)],
)  # fmt: skip
def test_cites_rule(rule, ok):
    assert cites_rule(rule) is ok


def test_verdict_fails_iff_blocker_regardless_of_claimed_verdict():
    assert v(BLOCKER, verdict="pass").verdict == "fail"
    assert v({"id": "m", "severity": "major", "summary": "x"}, verdict="fail").verdict == "pass"
    assert v(verdict="pass").verdict == "pass"  # empty findings with pass is valid


def test_uncited_blocker_is_downgraded_to_major():
    out = v({"id": "B", "severity": "blocker", "summary": "might break"})
    assert out.verdict == "pass" and out.findings[0].severity == "major"


def test_invalid_verdicts_are_none():
    assert normalise({"verdict": "pass"}) is None
    assert normalise([]) is None
    assert parse_verdict_text("not json") is None
    assert parse_verdict_text(None) is None
    bad_sev = {"reviewer": "r", "target": "t", "verdict": "pass",
               "findings": [{"id": "x", "severity": "huge", "summary": "s"}]}  # fmt: skip
    assert parse_verdict_text(json.dumps(bad_sev)) is None


def test_review_verdicts_missing_verdict_fails(pipeline):
    stage = pipeline.stage("design")
    sv = evaluate_stage(stage, [rec("ux"), rec("security_prd", verdict=None)], {}, "t")
    assert sv.failed_gate == "review_verdicts"
    assert "left no valid verdict" in sv.verdicts[0][1].findings[0].summary


def test_review_verdicts_blocker_fails_and_pass_passes(pipeline):
    stage = pipeline.stage("design")
    bad = evaluate_stage(stage, [rec("ux"), rec("security_prd", verdict=v(BLOCKER))], {}, "t")
    assert bad.failed_gate == "review_verdicts"
    assert bad.verdicts[0][1].findings[0].id == "security_prd/B-1"
    good = evaluate_stage(stage, [rec("ux"), rec("security_prd", verdict=v())], {}, "t")
    assert good.failed_gate is None and good.verdicts[0][1].verdict == "pass"


def test_step_that_did_not_end_done_fails_its_gate(pipeline):
    stage = pipeline.stage("build")
    records = [rec("dev", outcome=Outcome.BLOCKED)]
    sv = evaluate_stage(stage, records, {}, "t")
    assert sv.failed_gate == "tests"  # unlisted steps feed the first gate
    summaries = [f.summary for f in sv.verdicts[0][1].findings]
    assert "step 'dev' ended blocked" in summaries
    assert "step 'qa' did not run" in summaries


@pytest.mark.parametrize(
    ("value", "failed", "major"),
    [("PASS", False, False), ("CONCERNS", False, True), ("FAIL", True, False), (None, True, False)],
)
def test_readiness(pipeline, value, failed, major):
    stage = pipeline.stage("breakdown")
    files = {stage.stories_file: EPICS_OK}
    sv = evaluate_stage(stage, [rec("epics"), rec("readiness", status_value=value)], files, "t")
    assert (sv.failed_gate == "readiness") is failed
    assert any(f.severity == "major" for f in sv.verdicts[0][1].findings) is major
    assert (sv.stories is not None) is (not failed)


def test_stories_file_parse_errors_fail_the_stage_gate(pipeline):
    stage = pipeline.stage("breakdown")
    text = "```yaml\nkey: 1-1-a\ntitle: A\ndepends_on: []\ntouches: []\n```\n"
    records = [rec("epics"), rec("readiness", status_value="PASS")]
    sv = evaluate_stage(stage, records, {stage.stories_file: text}, "t")
    assert sv.failed_gate == "readiness"
    assert "story '1-1-a': missing 'sources'" in [f.summary for f in sv.verdicts[0][1].findings]
    assert sv.stories is None
    missing = evaluate_stage(stage, records, {}, "t")
    assert missing.failed_gate == "readiness"


def test_exception_inside_a_gate_fails_it(pipeline, monkeypatch):
    def boom(ctx):
        raise RuntimeError("kaput")

    monkeypatch.setitem(gates.GATES, "tests", boom)
    stage = pipeline.stage("build")
    names = ["dev", "qa", "tests", "code_review", "code_lens", "security"]
    records = [rec(n, verdict=v()) for n in names]
    sv = evaluate_stage(stage, records, {}, "t")
    assert sv.failed_gate == "tests"
    assert "kaput" in sv.verdicts[0][1].findings[0].summary


def test_docs_updated_needs_the_keeper_verdict(pipeline):
    stage = pipeline.stage("learn")
    others = [rec(n) for n in ("sprint_status", "open_pr", "ci_green", "merge_pr")]
    sv = evaluate_stage(stage, [rec("keeper", verdict=None), *others], {}, "t")
    assert sv.failed_gate == "docs_updated"
    no_update = v({"id": "D", "severity": "minor", "summary": "no update needed: test-only change"})
    sv = evaluate_stage(stage, [rec("keeper", verdict=no_update), *others], {}, "t")
    assert sv.failed_gate is None


def test_stepless_gate_takes_only_unclaimed_steps():
    from sdlc.core.pipeline import load_pipeline

    p = load_pipeline(
        """
settings: {run_budget_usd: 10, agents: {T: [s, x, worker, stub-worker, judge]}}
stages:
  - id: one
    entry: [full]
    budget_usd: 1
    steps:
      - {id: t, kind: script, script: tests}
      - {id: r, kind: judge, agent_role: T, prompt: p, tools: [Read], verdict_source: result_block}
    gate:
      - {kind: tests, steps: [t]}
      - review_verdicts
"""
    )
    stage = p.stage("one")
    assert [s.id for s in stage.gate_steps(stage.gate[1])] == ["r"]
    assert stage.gate_of_step("r").kind == "review_verdicts"
    sv = evaluate_stage(stage, [rec("t", outcome=Outcome.ERROR), rec("r", verdict=v())], {}, "x")
    tests_v, review_v = dict(sv.verdicts)["tests"], dict(sv.verdicts)["review_verdicts"]
    assert len(tests_v.findings) == 1 and tests_v.verdict == "fail"
    assert review_v.verdict == "pass"  # the failed `t` is not counted twice


def test_gate_verdicts_use_the_review_findings_shape(pipeline):
    sv = evaluate_stage(pipeline.stage("infra"), [rec("devops"), rec("terraform_plan")], {}, "t")
    kind, verdict = sv.verdicts[0]
    assert kind == "terraform_plan" and isinstance(verdict, Verdict)
    assert set(verdict.model_dump()) == {"reviewer", "target", "verdict", "findings"}
