"""`decide(pipeline, state, inputs) -> actions`: what the loop does next. Pure (AD-1).

Every action it returns changes the state once the app has carried it out, so the loop always
makes progress. When nothing is runnable, running or waiting it returns `Finish`; when the run
only waits for `resume_at` it returns `Wait(until)` (AD-1, AD-11, AD-18).
"""

from __future__ import annotations

from sdlc.core.models import (
    Action,
    BlockUnit,
    DeliverProposal,
    EvaluateGate,
    ExhaustBudget,
    Finish,
    OpenEscalation,
    Pipeline,
    ProposalStatus,
    ResetScope,
    RunKind,
    RunState,
    Stage,
    StartStep,
    StartUnit,
    Status,
    StepKind,
    StepRecord,
    TagStage,
    TickInputs,
    UnblockUnit,
    Unit,
    Wait,
)
from sdlc.core.stories import block_reason, serial_touches, waits_on

DONE = (Status.PASSED, Status.SKIPPED)


def step_cap(stage: Stage, kind: StepKind) -> float:
    """A step's budget cap. Scripts call no model and cost nothing."""
    return 0.0 if kind is StepKind.SCRIPT else stage.budget_usd


def round_records(state: RunState, unit: Unit) -> dict[str, StepRecord]:
    """The latest record of each step in the unit's current round. Interrupted and superseded
    attempts drop out, so the step runs again in the same round (AD-6, AD-18)."""
    out: dict[str, StepRecord] = {}
    for r in state.steps:
        if r.scope == unit.scope and r.stage == unit.stage and r.round == unit.round:
            out[r.step] = r
    return {k: r for k, r in out.items() if not r.interrupted and r.status is not Status.SUPERSEDED}


def branch_for(stage: Stage, run_id: str, subject: str | None, kind: RunKind = RunKind.FULL) -> str:
    """The unit's branch. A `fix` run's story branches are `fix/<key>` (AD-12, AD-27)."""
    branch = (stage.branch or "").format(run_id=run_id, key=subject or "")
    if kind is RunKind.FIX and stage.family == "story" and branch.startswith("story/"):
        return "fix/" + branch.removeprefix("story/")
    return branch


class _Tick:
    def __init__(self, pipeline: Pipeline, state: RunState, inputs: TickInputs) -> None:
        self.p = pipeline
        self.s = state
        self.inputs = inputs
        self.actions: list[Action] = []
        b = state.budget
        self.committed = b.spent + b.reserved
        self.no_more_starts = state.halted
        # AD-18: no step starts before resume_at.
        self.limit_wait = (
            state.resume_at is not None and inputs.now is not None and inputs.now < state.resume_at
        )
        # AD-11: once a step waits for budget, nothing queued after it starts in this tick.
        self.budget_wait = False
        self.running_steps = {r.scope for r in state.steps if r.status is Status.RUNNING}
        self.story_slots = {
            u.scope
            for u in state.units
            if u.status is Status.RUNNING and pipeline.stage(u.stage).family == "story"
        }
        self.learn_busy = any(
            u.status is Status.RUNNING and pipeline.stage(u.stage).family == "learn"
            for u in state.units
        )

    @property
    def holding(self) -> bool:
        """No step may start in this tick."""
        return self.no_more_starts or self.limit_wait or self.budget_wait

    def fits(self, cap: float) -> bool:
        """AD-11: start, wait for running steps to release their reservations, or escalate."""
        if self.holding:
            return False
        limit = self.s.budget.limit + 1e-9
        if self.committed + cap <= limit:
            self.committed += cap
            return True
        if self.s.budget.spent + cap <= limit:
            # Only reservations block it, so some step is running (or starts in this tick).
            self.budget_wait = True
            return False
        self.no_more_starts = True
        self.actions.append(
            ExhaustBudget(
                reason=(
                    f"run_budget_usd {self.s.budget.limit:.2f} reached: spent "
                    f"{self.s.budget.spent:.2f}, reserved {self.s.budget.reserved:.2f}, "
                    f"next cap {cap:.2f}"
                )
            )
        )
        return False


def decide(pipeline: Pipeline, state: RunState, inputs: TickInputs) -> tuple[Action, ...]:
    if state.status is not Status.RUNNING:
        return ()
    t = _Tick(pipeline, state, inputs)

    for e in state.escalations:
        if e.issue is None:
            t.actions.append(OpenEscalation(scope=e.scope, reason=e.reason))

    # AD-26: proposals are delivered between ticks and never block the run.
    for x in state.proposals:
        if x.status is ProposalStatus.PENDING:
            t.actions.append(DeliverProposal(sha256=x.sha256))

    for u in state.units:
        stage = pipeline.stage(u.stage)
        if u.status is Status.PASSED and stage.family == "run" and u.tagged is None:
            t.actions.append(
                TagStage(scope=u.scope, stage=u.stage, tag=f"sdlc/{state.run_id}/{u.stage}")
            )

    learn_next = _next_learn(t)
    for u in state.units:
        if u.status is Status.PENDING:
            _pending(t, u, learn_next)
        elif u.status is Status.BLOCKED:
            if block_reason(pipeline, state, u, inputs.approved_domains) is None:
                t.actions.append(UnblockUnit(scope=u.scope, stage=u.stage))
        elif u.status is Status.RUNNING:
            _running(t, u)

    if not t.actions and not t.running_steps:
        if t.limit_wait and not state.halted and _has_work(state):
            assert state.resume_at is not None
            t.actions.append(Wait(until=state.resume_at))  # waiting is not finished (AD-11)
        else:
            all_done = all(u.status in DONE for u in state.units) and not state.halted
            t.actions.append(Finish(exit_code=0 if all_done else 2))
    return tuple(t.actions)


def _has_work(state: RunState) -> bool:
    """Some unit could still start or continue once the wait is over."""
    return any(u.status in (Status.PENDING, Status.RUNNING) for u in state.units)


def _next_learn(t: _Tick) -> Unit | None:
    """AD-21: learn scopes run one at a time, in the order they were queued."""
    if t.learn_busy:
        return None
    ready = [
        u
        for u in t.s.units
        if u.status is Status.PENDING
        and t.p.stage(u.stage).family == "learn"
        and u.queued is not None
        and not waits_on(t.p, t.s, u)
        and block_reason(t.p, t.s, u, t.inputs.approved_domains) is None
    ]
    return min(ready, key=lambda u: u.queued or 0) if ready else None


def _pending(t: _Tick, u: Unit, learn_next: Unit | None) -> None:
    reason = block_reason(t.p, t.s, u, t.inputs.approved_domains)
    if reason is not None:
        t.actions.append(BlockUnit(scope=u.scope, stage=u.stage, reason=reason))
        return
    if t.holding or waits_on(t.p, t.s, u):
        return
    stage = t.p.stage(u.stage)
    if stage.family == "learn" and u is not learn_next:
        return
    if stage.family == "story":
        if u.scope not in t.story_slots and len(t.story_slots) >= t.p.settings.max_parallel:
            return
        mine = serial_touches(t.p, t.s, u.subject or "")
        for other in t.story_slots - {u.scope}:
            if mine & serial_touches(t.p, t.s, other.split(":", 1)[1]):
                return
        t.story_slots.add(u.scope)
    t.actions.append(
        StartUnit(
            scope=u.scope,
            stage=u.stage,
            branch=branch_for(stage, t.s.run_id, u.subject, t.s.kind),
        )
    )


def _running(t: _Tick, u: Unit) -> None:
    scope = t.s.scope(u.scope)
    if scope is None:
        return
    if scope.needs_reset:
        if u.scope not in t.running_steps:
            t.actions.append(ResetScope(scope=u.scope))
        return
    stage = t.p.stage(u.stage)
    recs = round_records(t.s, u)
    running = any(r.status is Status.RUNNING for r in recs.values())
    failed = any(r.status not in (Status.RUNNING, Status.PASSED) for r in recs.values())
    if failed:
        if not running:
            t.actions.append(EvaluateGate(scope=u.scope, stage=u.stage, round=u.round))
        return
    for phase in stage.phases():
        missing = [s for s in phase if s.id not in recs]
        if missing:
            for s in missing:
                cap = step_cap(stage, s.kind)
                if not t.fits(cap):
                    return
                t.actions.append(
                    StartStep(scope=u.scope, stage=u.stage, step=s.id, round=u.round, cap=cap)
                )
            return
        if any(recs[s.id].status is Status.RUNNING for s in phase):
            return
    t.actions.append(EvaluateGate(scope=u.scope, stage=u.stage, round=u.round))
