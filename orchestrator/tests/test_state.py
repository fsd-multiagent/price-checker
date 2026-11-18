"""core/state.py: legal AD-5 transitions, round counters, head/base SHAs, budget reservation."""

from __future__ import annotations

import pytest

from sdlc.core.models import (
    CoreError,
    GateEvaluated,
    Merged,
    RunKind,
    RunResumed,
    RunStarted,
    Status,
    StepFinished,
    StepInterrupted,
    StepResult,
    StepStarted,
    StoriesLoaded,
    Story,
    UnitStarted,
    Verdict,
    WorktreeReady,
)
from sdlc.core.state import TRANSITIONS, apply, initial_state, legal

T = "2026-11-16T09:00:00Z"
FAIL = Verdict(reviewer="gate:x", target="t", verdict="fail")
PASS = Verdict(reviewer="gate:x", target="t", verdict="pass")
DONE = StepResult(result='```json\n{"status": "done"}\n```', total_cost_usd=0.5)
STORIES = (
    Story(key="1-1-a", title="A", sources=()),
    Story(key="1-2-b", title="B", depends_on=("1-1-a",), sources=()),
)


def started(pipeline, from_stage=None):
    s = initial_state(
        pipeline, run_id="r1", kind=RunKind.FULL, at=T, dry_run=True, from_stage=from_stage
    )
    return apply(pipeline, s, RunStarted(at=T))


def with_unit_running(pipeline, scope="stage:analysis", stage="analysis", state=None):
    s = state or started(pipeline)
    s = apply(
        pipeline,
        s,
        WorktreeReady(at=T, scope=scope, branch="b", worktree="/wt", head_sha="h0"),
    )
    return apply(pipeline, s, UnitStarted(at=T, scope=scope, stage=stage))


def test_transition_table_is_the_ad5_diagram():
    S = Status
    edges = {(a, b) for a, targets in TRANSITIONS.items() for b in targets}
    assert edges == {
        (S.PENDING, S.RUNNING), (S.PENDING, S.BLOCKED), (S.PENDING, S.SKIPPED),
        (S.BLOCKED, S.PENDING),
        (S.RUNNING, S.PASSED), (S.RUNNING, S.FAILED), (S.RUNNING, S.BLOCKED),
        (S.RUNNING, S.ESCALATED), (S.RUNNING, S.SUPERSEDED),
        (S.PASSED, S.PENDING),
        (S.FAILED, S.PENDING), (S.FAILED, S.ESCALATED),
        (S.ESCALATED, S.PENDING),
        # AD-27, the run only: owner-review waits end the run; a later command resumes or
        # escalates it.
        (S.RUNNING, S.AWAITING_OWNER), (S.AWAITING_OWNER, S.PENDING),
        (S.AWAITING_OWNER, S.ESCALATED),
        # Issue #72, the run only: an owner pause or stop; `sdlc resume` continues it.
        (S.RUNNING, S.PAUSED), (S.PAUSED, S.PENDING),
    }  # fmt: skip
    assert not legal(S.PASSED, S.RUNNING)
    assert not legal(S.SKIPPED, S.PENDING)


def test_initial_state_has_run_units_only_and_from_skips(pipeline):
    s = started(pipeline, from_stage="architecture")
    assert s.status is Status.RUNNING
    by = {u.stage: u.status for u in s.units}
    assert by["analysis"] is Status.SKIPPED and by["design"] is Status.SKIPPED
    assert by["architecture"] is Status.PENDING
    assert "build" not in by  # story units come with the stories
    assert s.budget.limit == pipeline.settings.run_budget_usd


def test_illegal_transition_is_a_core_error(pipeline):
    s = with_unit_running(pipeline)
    with pytest.raises(CoreError, match="illegal transition running -> running"):
        apply(pipeline, s, UnitStarted(at=T, scope="stage:analysis", stage="analysis"))


def test_step_start_reserves_budget_and_records_base_sha(pipeline):
    s = with_unit_running(pipeline)
    s = apply(
        pipeline,
        s,
        StepStarted(
            at=T, seq=1, scope="stage:analysis", stage="analysis", step="brief", round=1, cap=2.0
        ),
    )
    assert s.budget.reserved == 2.0
    rec = s.step_record(1)
    assert rec.status is Status.RUNNING and rec.base_sha == "h0"
    s = apply(pipeline, s, StepFinished(at=T, seq=1, result=DONE, head_sha="h1"))
    assert s.budget.reserved == 0 and s.budget.spent == 0.5
    assert s.step_record(1).status is Status.PASSED
    assert s.scope("stage:analysis").head_sha == "h1"


def test_failed_step_does_not_move_the_head(pipeline):
    s = with_unit_running(pipeline)
    s = apply(
        pipeline,
        s,
        StepStarted(
            at=T, seq=1, scope="stage:analysis", stage="analysis", step="brief", round=1, cap=2.0
        ),
    )
    bad = StepResult(subtype="error_max_budget_usd", is_error=True, errors=("cap",))
    s = apply(pipeline, s, StepFinished(at=T, seq=1, result=bad, head_sha="h9"))
    assert s.step_record(1).status is Status.FAILED
    assert s.step_record(1).outcome == "error"
    assert s.scope("stage:analysis").head_sha == "h0"


def test_step_seq_must_match(pipeline):
    s = with_unit_running(pipeline)
    with pytest.raises(CoreError, match="next_seq"):
        apply(
            pipeline,
            s,
            StepStarted(
                at=T, seq=7, scope="stage:analysis", stage="analysis", step="brief", round=1, cap=1
            ),
        )


def gate(s, pipeline, failed, scope="stage:analysis", stage="analysis", rnd=None):
    u = s.unit(scope, stage)
    return apply(
        pipeline,
        s,
        GateEvaluated(
            at=T,
            scope=scope,
            stage=stage,
            round=rnd or u.round,
            verdicts=(("judge_coverage", FAIL if failed else PASS),),
            failed_gate="judge_coverage" if failed else None,
            feedback=("verdicts/x.json",) if failed else (),
        ),
    )


def test_gate_fail_counts_rounds_then_escalates(pipeline):
    s = with_unit_running(pipeline)
    for n in (1, 2):
        s = gate(s, pipeline, failed=True)
        u = s.unit("stage:analysis", "analysis")
        assert u.status is Status.PENDING and s.counter("stage:analysis", "judge_coverage") == n
        assert u.feedback == ("verdicts/x.json",)
        assert s.scope("stage:analysis").needs_reset
        s = apply(pipeline, s, UnitStarted(at=T, scope="stage:analysis", stage="analysis"))
    s = gate(s, pipeline, failed=True)
    u = s.unit("stage:analysis", "analysis")
    assert u.status is Status.ESCALATED and u.escalated_gate == "judge_coverage"
    assert s.escalation("stage:analysis") is not None


def test_resume_resets_only_the_escalated_counter(pipeline):
    s = with_unit_running(pipeline)
    for _ in range(3):
        s = gate(s, pipeline, failed=True)
        if s.unit("stage:analysis", "analysis").status is Status.PENDING:
            s = apply(pipeline, s, UnitStarted(at=T, scope="stage:analysis", stage="analysis"))
    s = s.model_validate(
        {
            **s.model_dump(),
            "counters": [
                *s.model_dump()["counters"],
                {"scope": "stage:analysis", "gate": "other", "fails": 1},
            ],
            "status": "escalated",
        }
    )
    s = apply(pipeline, s, RunResumed(at=T))
    assert s.status is Status.RUNNING
    assert s.unit("stage:analysis", "analysis").status is Status.PENDING
    assert s.counter("stage:analysis", "judge_coverage") == 0
    assert s.counter("stage:analysis", "other") == 1


def test_on_fail_rewind_invalidates_the_target(pipeline):
    s = started(pipeline)
    s = apply(pipeline, s, StoriesLoaded(at=T, stories=STORIES))
    scope = "story:1-1-a"
    s = with_unit_running(pipeline, scope, "build", s)
    s = gate(s, pipeline, failed=False, scope=scope, stage="build")
    assert s.unit(scope, "build").status is Status.PASSED
    s = apply(pipeline, s, UnitStarted(at=T, scope=scope, stage="integrate"))
    failed = GateEvaluated(
        at=T,
        scope=scope,
        stage="integrate",
        round=1,
        verdicts=(("ci_green", FAIL),),
        failed_gate="ci_green",
        feedback=("v.json",),
    )
    s = apply(pipeline, s, failed)
    build = s.unit(scope, "build")
    assert build.status is Status.PENDING
    assert build.invalidated_by == "integrate:ci_green"
    assert build.feedback == ("v.json",)
    assert s.unit(scope, "integrate").status is Status.PENDING
    assert s.counter(scope, "ci_green") == 1 and s.counter(scope, "tests") == 0


def test_stories_create_story_learn_and_epic_units(pipeline):
    s = apply(pipeline, started(pipeline), StoriesLoaded(at=T, stories=STORIES))
    scopes = {(u.scope, u.stage) for u in s.units}
    assert ("story:1-2-b", "integrate") in scopes
    assert ("learn:1-1-a", "learn") in scopes
    assert ("learn:epic-1", "retro") in scopes


def test_merge_enqueues_learn_and_epic_retro(pipeline):
    s = apply(pipeline, started(pipeline), StoriesLoaded(at=T, stories=STORIES))
    for key in ("1-1-a", "1-2-b"):
        s = apply(
            pipeline,
            s,
            WorktreeReady(at=T, scope=f"story:{key}", branch="b", worktree=f"/{key}", head_sha="h"),
        )
    s = apply(pipeline, s, Merged(at=T, scope="story:1-1-a", sha="m1"))
    assert s.unit("learn:1-1-a", "learn").queued == 1
    assert s.unit("learn:epic-1", "retro").queued is None
    s = apply(pipeline, s, Merged(at=T, scope="story:1-2-b", sha="m2"))
    assert s.unit("learn:1-2-b", "learn").queued == 2
    assert s.unit("learn:epic-1", "retro").queued == 3


def test_interrupted_step_releases_its_reservation(pipeline):
    s = with_unit_running(pipeline)
    s = apply(
        pipeline,
        s,
        StepStarted(
            at=T, seq=1, scope="stage:analysis", stage="analysis", step="brief", round=1, cap=2.0
        ),
    )
    s = apply(pipeline, s, StepInterrupted(at=T, seq=1))
    rec = s.step_record(1)
    assert rec.interrupted and rec.status is Status.FAILED
    assert s.budget.reserved == 0
    assert s.budget.spent == 2.0  # the unknown cost of a killed step is charged at its cap


def test_crash_then_budget_counts_the_interrupted_cap(pipeline):
    from sdlc.core.models import ExhaustBudget, TickInputs
    from sdlc.core.scheduler import decide

    s = with_unit_running(pipeline)
    s = s.model_validate({**s.model_dump(), "budget": {"limit": 3.0}})
    s = apply(
        pipeline,
        s,
        StepStarted(
            at=T, seq=1, scope="stage:analysis", stage="analysis", step="brief", round=1, cap=2.0
        ),
    )
    s = apply(pipeline, s, StepInterrupted(at=T, seq=1))
    # 2.0 charged + next cap 2.0 > 3.0: the rerun may not start.
    actions = decide(pipeline, s, TickInputs())
    assert [type(a) for a in actions] == [ExhaustBudget]


def test_resume_of_a_blocked_run(pipeline):
    s = started(pipeline)
    s = s.model_validate({**s.model_dump(), "status": "blocked", "exit_code": 2})
    s = apply(pipeline, s, RunResumed(at=T))
    assert s.status is Status.RUNNING and s.exit_code is None


def test_resume_of_a_pending_run_crashed_before_it_started(pipeline):
    s = initial_state(pipeline, run_id="r1", kind=RunKind.FULL, at=T, dry_run=True)
    assert s.status is Status.PENDING
    assert apply(pipeline, s, RunResumed(at=T)).status is Status.RUNNING


def test_stories_loaded_again_replaces_stories_and_units(pipeline):
    s = apply(pipeline, started(pipeline), StoriesLoaded(at=T, stories=STORIES))
    new = (Story(key="1-9-z", title="Z", sources=()),)
    s = apply(pipeline, s, StoriesLoaded(at=T, stories=new))
    assert [x.key for x in s.stories] == ["1-9-z"]
    scopes = {u.scope for u in s.units if not u.scope.startswith("stage:")}
    assert scopes == {"story:1-9-z", "learn:1-9-z", "learn:epic-1"}


def test_ambiguous_step_escalates_the_unit_at_once(pipeline):
    s = with_unit_running(pipeline)
    s = apply(
        pipeline,
        s,
        StepStarted(
            at=T, seq=1, scope="stage:analysis", stage="analysis", step="brief", round=1, cap=1
        ),
    )
    amb = StepResult(result='{"status": "ambiguous"}')
    s = apply(pipeline, s, StepFinished(at=T, seq=1, result=amb))
    assert s.unit("stage:analysis", "analysis").status is Status.ESCALATED
    assert s.counter("stage:analysis", "judge_coverage") == 0
