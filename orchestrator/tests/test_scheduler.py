"""core/scheduler.py: stage chain, story readiness, learn queue, budget, exit condition."""

from __future__ import annotations

from sdlc.core.models import (
    BlockUnit,
    BudgetExhausted,
    EvaluateGate,
    ExhaustBudget,
    Finish,
    GateEvaluated,
    Merged,
    OpenEscalation,
    ResetScope,
    RunKind,
    RunStarted,
    StartStep,
    StartUnit,
    StepStarted,
    StoriesLoaded,
    Story,
    TickInputs,
    UnblockUnit,
    UnitStarted,
    WorktreeReady,
)
from sdlc.core.scheduler import decide
from sdlc.core.state import apply, initial_state

T = "2026-11-16T09:00:00Z"
NONE = TickInputs()


def started(pipeline, from_stage=None):
    s = initial_state(
        pipeline, run_id="r1", kind=RunKind.FULL, at=T, dry_run=True, from_stage=from_stage
    )
    return apply(pipeline, s, RunStarted(at=T))


def kinds(actions):
    return [type(a).__name__ for a in actions]


def run_unit(pipeline, s, scope, stage):
    if s.scope(scope) is None:
        s = apply(
            pipeline,
            s,
            WorktreeReady(at=T, scope=scope, branch="b", worktree=f"/{scope}", head_sha="h"),
        )
    return apply(pipeline, s, UnitStarted(at=T, scope=scope, stage=stage))


def pass_unit(pipeline, s, scope, stage):
    s = run_unit(pipeline, s, scope, stage)
    u = s.unit(scope, stage)
    return apply(
        pipeline, s, GateEvaluated(at=T, scope=scope, stage=stage, round=u.round, verdicts=())
    )


def at_stories(pipeline, stories):
    """A state where every run-level stage before the stories has passed."""
    s = started(pipeline, from_stage="build")
    return apply(pipeline, s, StoriesLoaded(at=T, stories=stories))


def settle(pipeline, s, inputs=NONE):
    """Apply block and unblock decisions until none are left; they cascade one tick at a time."""
    from sdlc.core.models import UnitBlocked, UnitUnblocked

    while True:
        actions = decide(pipeline, s, inputs)
        moves = [a for a in actions if isinstance(a, BlockUnit | UnblockUnit)]
        if not moves:
            return s, actions
        for a in moves:
            if isinstance(a, BlockUnit):
                s = apply(
                    pipeline, s, UnitBlocked(at=T, scope=a.scope, stage=a.stage, reason=a.reason)
                )
            else:
                s = apply(pipeline, s, UnitUnblocked(at=T, scope=a.scope, stage=a.stage))


def test_first_decision_starts_the_first_stage(pipeline):
    actions = decide(pipeline, started(pipeline), NONE)
    assert actions == (StartUnit(scope="stage:analysis", stage="analysis", branch="sdlc/r1"),)


def test_running_unit_starts_its_first_phase_then_evaluates(pipeline):
    s = run_unit(pipeline, started(pipeline), "stage:design", "design")
    s = s.model_validate({**s.model_dump(), "units": [
        {**u, "status": "passed"} if u["stage"] in ("analysis", "requirements") else u
        for u in s.model_dump()["units"]]})  # fmt: skip
    actions = [a for a in decide(pipeline, s, NONE) if isinstance(a, StartStep)]
    assert [a.step for a in actions] == ["ux", "security_prd"]  # one parallel group
    assert all(a.cap == 2 for a in actions)


def test_gate_evaluated_when_all_steps_passed(pipeline):
    s = run_unit(pipeline, started(pipeline), "stage:infra", "infra")
    s = apply(
        pipeline,
        s,
        StepStarted(at=T, seq=1, scope="stage:infra", stage="infra", step="devops", round=1, cap=3),
    )
    from sdlc.core.models import StepFinished, StepResult

    s = apply(
        pipeline, s, StepFinished(at=T, seq=1, result=StepResult(result='{"status": "done"}'))
    )
    assert StartStep(
        scope="stage:infra", stage="infra", step="terraform_plan", round=1, cap=0.0
    ) in decide(pipeline, s, NONE)
    s = apply(
        pipeline,
        s,
        StepStarted(
            at=T, seq=2, scope="stage:infra", stage="infra", step="terraform_plan", round=1, cap=0
        ),
    )
    s = apply(pipeline, s, StepFinished(at=T, seq=2, result=StepResult(exit_code=0)))
    # The infra branch then goes through its own PR (open_pr, ci_green, merge_pr).
    assert StartStep(
        scope="stage:infra", stage="infra", step="open_pr", round=1, cap=0.0
    ) in decide(pipeline, s, NONE)
    s = s.model_validate(
        {**s.model_dump(), "steps": [*s.model_dump()["steps"], *(
            {**s.model_dump()["steps"][1], "seq": 3 + i, "step": name}
            for i, name in enumerate(("open_pr", "ci_green", "merge_pr")))]}
    )  # fmt: skip
    assert EvaluateGate(scope="stage:infra", stage="infra", round=1) in decide(pipeline, s, NONE)


def test_dependent_story_waits_for_merge(pipeline):
    stories = (
        Story(key="1-1-a", title="A", sources=()),
        Story(key="1-2-b", title="B", depends_on=("1-1-a",), sources=()),
    )
    s = at_stories(pipeline, stories)
    starts = [a for a in decide(pipeline, s, NONE) if isinstance(a, StartUnit)]
    assert [a.scope for a in starts] == ["story:1-1-a"]
    s = pass_unit(pipeline, s, "story:1-1-a", "build")
    s = run_unit(pipeline, s, "story:1-1-a", "integrate")
    s = apply(pipeline, s, Merged(at=T, scope="story:1-1-a", sha="m"))
    starts = [a for a in decide(pipeline, s, NONE) if isinstance(a, StartUnit)]
    assert "story:1-2-b" in [a.scope for a in starts]


def test_unapproved_source_blocks_and_unblocks(pipeline):
    s = at_stories(pipeline, (Story(key="1-1-a", title="A", sources=("shop.example",)),))
    actions = decide(pipeline, s, NONE)
    assert any(isinstance(a, BlockUnit) and "shop.example" in a.reason for a in actions)
    s, actions = settle(pipeline, s)
    # Still blocked: nothing runs, so the run finishes with exit 2.
    assert actions == (Finish(exit_code=2),)
    assert s.unit("story:1-1-a", "build").status == "blocked"
    assert s.unit("story:1-1-a", "build").round == 0
    approved = TickInputs(approved_domains=frozenset({"shop.example"}))
    assert UnblockUnit(scope="story:1-1-a", stage="build") in decide(pipeline, s, approved)
    s, actions = settle(pipeline, s, approved)
    assert s.unit("story:1-1-a", "build").status == "pending"
    assert any(isinstance(a, StartUnit) for a in actions)


def test_max_parallel_and_serial_paths(pipeline):
    stories = tuple(
        Story(
            key=f"1-{i}-s",
            title="S",
            touches=("api/openapi.yaml",) if i in (1, 2) else (),
            sources=(),
        )
        for i in range(1, 6)
    )
    s = at_stories(pipeline, stories)
    starts = [a.scope for a in decide(pipeline, s, NONE) if isinstance(a, StartUnit)]
    # 1-1 takes the openapi lock, so 1-2 waits; 1-3 and 1-4 fill the three slots.
    assert starts == ["story:1-1-s", "story:1-3-s", "story:1-4-s"]


def test_serial_paths_match_by_prefix_both_ways(pipeline):
    from sdlc.core.stories import path_overlaps

    assert path_overlaps("api/", "api/openapi.yaml")
    assert path_overlaps("api/openapi.yaml", "api")
    assert not path_overlaps("api-docs/", "api/openapi.yaml")
    stories = (
        Story(key="1-1-a", title="A", touches=("api/",), sources=()),
        Story(key="1-2-b", title="B", touches=("api/openapi.yaml",), sources=()),
    )
    s = at_stories(pipeline, stories)
    starts = [a.scope for a in decide(pipeline, s, NONE) if isinstance(a, StartUnit)]
    assert starts == ["story:1-1-a"]


def test_learn_queue_runs_one_at_a_time_in_merge_order(pipeline):
    stories = (Story(key="1-1-a", title="A", sources=()), Story(key="1-2-b", title="B", sources=()))
    s = at_stories(pipeline, stories)
    for key in ("1-2-b", "1-1-a"):
        s = pass_unit(pipeline, s, f"story:{key}", "build")
        s = run_unit(pipeline, s, f"story:{key}", "integrate")
        s = apply(pipeline, s, Merged(at=T, scope=f"story:{key}", sha=key))
        u = s.unit(f"story:{key}", "integrate")
        s = apply(
            pipeline,
            s,
            GateEvaluated(
                at=T, scope=f"story:{key}", stage="integrate", round=u.round, verdicts=()
            ),
        )
    starts = [a.scope for a in decide(pipeline, s, NONE) if isinstance(a, StartUnit)]
    assert starts == ["learn:1-2-b"]
    s = run_unit(pipeline, s, "learn:1-2-b", "learn")
    assert not [a for a in decide(pipeline, s, NONE) if isinstance(a, StartUnit)]


def test_budget_cap_stops_new_steps(pipeline):
    s = run_unit(pipeline, started(pipeline), "stage:analysis", "analysis")
    s = s.model_validate({**s.model_dump(), "budget": {"limit": 1.0}})
    actions = decide(pipeline, s, NONE)
    assert kinds(actions) == ["ExhaustBudget"]
    assert isinstance(actions[0], ExhaustBudget)
    s = apply(pipeline, s, BudgetExhausted(at=T, reason="cap"))
    actions = decide(pipeline, s, NONE)
    assert kinds(actions) == ["OpenEscalation"]
    assert actions[0] == OpenEscalation(scope="run", reason="cap")


def test_reset_scope_before_a_retry(pipeline):
    s = run_unit(pipeline, started(pipeline), "stage:analysis", "analysis")
    s = s.model_validate(
        {
            **s.model_dump(),
            "scopes": [{**sc, "needs_reset": True} for sc in s.model_dump()["scopes"]],
        }
    )
    assert decide(pipeline, s, NONE) == (ResetScope(scope="stage:analysis"),)


def test_finish_exit_code_zero_when_all_passed(pipeline):
    s = started(pipeline)
    s = s.model_validate(
        {
            **s.model_dump(),
            "units": [{**u, "status": "passed", "tagged": "t"} for u in s.model_dump()["units"]],
        }
    )
    assert decide(pipeline, s, NONE) == (Finish(exit_code=0),)


def test_passed_run_stage_is_tagged(pipeline):
    s = pass_unit(pipeline, started(pipeline), "stage:analysis", "analysis")
    tags = [a for a in decide(pipeline, s, NONE) if type(a).__name__ == "TagStage"]
    assert tags[0].tag == "sdlc/r1/analysis"


def test_dependency_on_escalated_story_blocks(pipeline):
    stories = (
        Story(key="1-1-a", title="A", sources=()),
        Story(key="1-2-b", title="B", depends_on=("1-1-a",), sources=()),
    )
    s = at_stories(pipeline, stories)
    s = s.model_validate(
        {
            **s.model_dump(),
            "units": [
                {**u, "status": "escalated"}
                if u["scope"] == "story:1-1-a" and u["stage"] == "build"
                else u
                for u in s.model_dump()["units"]
            ],
        }
    )
    s, _ = settle(pipeline, s)
    blocked = {(u.scope, u.stage) for u in s.units if u.status == "blocked"}
    assert {
        ("story:1-2-b", "build"),
        ("learn:1-1-a", "learn"),
        ("learn:epic-1", "retro"),
    } <= blocked
