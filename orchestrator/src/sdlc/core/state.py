"""`apply(pipeline, state, event) -> state`: the only way state changes (AD-4, AD-5).

The state is dumped to plain data, changed, and rebuilt with `model_validate`.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from sdlc.core.gates import last_verdict_block, parse_verdict_text
from sdlc.core.models import (
    AwaitedSettled,
    BudgetExhausted,
    CoreError,
    EscalationOpened,
    Event,
    GateEvaluated,
    IntakeRecorded,
    IntakeRejected,
    Merged,
    Outcome,
    OwnerReviewAwaited,
    PathsReverted,
    PauseRequested,
    Pipeline,
    PrOpened,
    ProposalCaptured,
    ProposalDelivered,
    ProposalFailed,
    ProposalStatus,
    RunFinished,
    RunKind,
    RunPaused,
    RunResumed,
    RunStarted,
    RunState,
    RunStopped,
    ScopeReset,
    Stage,
    Status,
    StepFinished,
    StepInterrupted,
    StepKind,
    StepResult,
    StepStarted,
    StoriesLoaded,
    Story,
    Tagged,
    UnitBlocked,
    UnitStarted,
    UnitUnblocked,
    UsageLimited,
    UsageRecord,
    UsageWarning,
    VerdictSource,
    WorktreeReady,
)
from sdlc.core.outcome import derive_outcome
from sdlc.core.pipeline import ancestors
from sdlc.core.usage import UNKNOWN_ROLE, is_measured, sum_models

S = Status
TRANSITIONS: dict[Status, frozenset[Status]] = {
    S.PENDING: frozenset({S.RUNNING, S.BLOCKED, S.SKIPPED}),
    S.BLOCKED: frozenset({S.PENDING}),
    S.RUNNING: frozenset(
        {S.PASSED, S.FAILED, S.BLOCKED, S.ESCALATED, S.SUPERSEDED, S.AWAITING_OWNER, S.PAUSED}
    ),
    S.PASSED: frozenset({S.PENDING}),
    S.FAILED: frozenset({S.PENDING, S.ESCALATED}),
    S.ESCALATED: frozenset({S.PENDING}),
    S.SKIPPED: frozenset(),
    S.SUPERSEDED: frozenset(),
    # AD-27, the run only: a later command resumes it (merged) or escalates it (closed).
    S.AWAITING_OWNER: frozenset({S.PENDING, S.ESCALATED}),
    # Issue #72, the run only: an owner pause or stop; `sdlc resume` continues it.
    S.PAUSED: frozenset({S.PENDING}),
}


def legal(old: Status, new: Status) -> bool:
    return new in TRANSITIONS[old]


def _move(node: dict[str, Any], new: Status, what: str) -> None:
    old = Status(node["status"])
    if new is S.SUPERSEDED and "seq" not in node:  # AD-5: step records only
        raise CoreError(f"illegal transition {old} -> {new} for {what}: step records only")
    if new in (S.AWAITING_OWNER, S.PAUSED) and "run_id" not in node:  # the run only
        raise CoreError(f"illegal transition {old} -> {new} for {what}: the run only")
    if not legal(old, new):
        raise CoreError(f"illegal transition {old} -> {new} for {what}")
    node["status"] = new


# --------------------------------------------------------------------------- scopes


def unit_scope(stage: Stage, subject: str | None) -> str:
    if stage.family == "run":
        return f"stage:{stage.id}"
    if stage.family == "story":
        return f"story:{subject}"
    return f"learn:{subject}"


def epic_subject(epic: int) -> str:
    return f"epic-{epic}"


def initial_state(
    pipeline: Pipeline,
    *,
    run_id: str,
    kind: RunKind,
    at: str,
    dry_run: bool,
    from_stage: str | None = None,
    fixtures: str | None = None,
) -> RunState:
    if from_stage is not None:
        pipeline.stage(from_stage)  # raises KeyError on an unknown stage
    start = pipeline.index(from_stage) if from_stage else 0
    units = []
    for i, stage in enumerate(pipeline.stages):
        if kind not in stage.entry or stage.family != "run":
            continue
        units.append(
            {
                "scope": unit_scope(stage, None),
                "stage": stage.id,
                "status": S.SKIPPED if i < start else S.PENDING,
                "reason": f"run starts at '{from_stage}'" if i < start else None,
            }
        )
    return RunState.model_validate(
        {
            "run_id": run_id,
            "kind": kind,
            "dry_run": dry_run,
            "created_at": at,
            "from_stage": from_stage,
            "budget": {"limit": pipeline.settings.run_budget_usd},
            "units": units,
            "fixtures": fixtures,
        }
    )


# --------------------------------------------------------------------------- apply


def apply(pipeline: Pipeline, state: RunState, event: Event) -> RunState:
    d = state.model_dump(mode="json")
    handler = _HANDLERS.get(type(event))
    if handler is None:
        raise CoreError(f"no handler for event {type(event).__name__}")
    handler(pipeline, d, event)
    # AD-11: reserved is always the sum of the caps of the running steps.
    d["budget"]["reserved"] = sum(r["cap"] for r in d["steps"] if r["status"] == S.RUNNING)
    return RunState.model_validate(d)


def parse_time(at: str) -> datetime:
    return datetime.strptime(at, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=UTC)


def format_time(t: datetime) -> str:
    return t.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def _unit(d: dict[str, Any], scope: str, stage: str) -> dict[str, Any]:
    for u in d["units"]:
        if u["scope"] == scope and u["stage"] == stage:
            return u
    raise CoreError(f"no unit {scope}/{stage}")


def _scope(d: dict[str, Any], scope: str) -> dict[str, Any]:
    for s in d["scopes"]:
        if s["id"] == scope:
            return s
    raise CoreError(f"no scope {scope}")


def _record(d: dict[str, Any], seq: int) -> dict[str, Any]:
    for r in d["steps"]:
        if r["seq"] == seq:
            return r
    raise CoreError(f"no step record {seq}")


def _escalate_record(d: dict[str, Any], scope: str, reason: str) -> None:
    for e in d["escalations"]:
        if e["scope"] == scope:
            if scope == "run" and reason not in e["reason"]:
                # A second run-level escalation keeps the first reason and is reported again:
                # OpenEscalation reuses the issue by its marker and comments (AD-13).
                e["reason"] = f"{e['reason']}; then: {reason}"
                e["issue"] = None
            elif scope != "run":
                e["reason"] = reason
            return
    d["escalations"].append({"scope": scope, "reason": reason, "issue": None})


def _run_started(p: Pipeline, d: dict[str, Any], e: RunStarted) -> None:
    _move(d, S.RUNNING, "run")


def _run_resumed(p: Pipeline, d: dict[str, Any], e: RunResumed) -> None:
    status = Status(d["status"])
    undelivered = any(x["status"] == ProposalStatus.FAILED for x in d["proposals"])
    if status is S.PASSED and not undelivered:
        raise CoreError("run already passed; nothing to resume")
    if status is S.PASSED:  # AD-26: only to deliver the proposals that failed
        _move(d, S.PENDING, "run")
    if status in (S.ESCALATED, S.BLOCKED, S.AWAITING_OWNER, S.PAUSED):
        _move(d, S.PENDING, "run")
    # Issue #72: a resume ends the pause (a pause still under way when the process died, too).
    d["pause_kind"] = None
    d["pause_requested_at"] = None
    d["paused_at"] = None
    if Status(d["status"]) is S.PENDING:  # also a crash before RunStarted was saved
        _move(d, S.RUNNING, "run")
    d["exit_code"] = None
    d["budget"]["exhausted"] = False
    # AD-18: a resume clears the wait and the limit episode.
    d["resume_at"] = None
    d["limit_episode_start"] = None
    d["limit_exceeded"] = False
    if e.budget_limit is not None:
        d["budget"]["limit"] = e.budget_limit
    for x in d["proposals"]:  # AD-26: a failed delivery is tried again
        if x["status"] == ProposalStatus.FAILED:
            x["status"] = ProposalStatus.PENDING
    for u in d["units"]:
        if u["status"] == S.ESCALATED:
            _move(u, S.PENDING, f"unit {u['scope']}/{u['stage']}")
            gate = u.get("escalated_gate")
            if gate:  # AD-6: only the escalated (scope, gate) counter is reset
                for c in d["counters"]:
                    if c["scope"] == u["scope"] and c["gate"] == gate:
                        c["fails"] = 0
            u["escalated_gate"] = None
            u["reason"] = "resumed"


def _stories_loaded(p: Pipeline, d: dict[str, Any], e: StoriesLoaded) -> None:
    _load_stories(p, d, e.stories)


def _load_stories(p: Pipeline, d: dict[str, Any], stories: tuple[Story, ...]) -> None:
    if d["stories_loaded"]:  # the breakdown passed again: its stories replace the old ones
        d["units"] = [u for u in d["units"] if p.stage(u["stage"]).family == "run"]
    d["stories"] = [s.model_dump() for s in stories]
    d["stories_loaded"] = True
    kind = RunKind(d["kind"])
    start = p.index(d["from_stage"]) if d["from_stage"] else 0
    epics = sorted({s.epic for s in stories})
    for i, stage in enumerate(p.stages):
        if kind not in stage.entry or stage.family == "run":
            continue
        subjects = (
            [epic_subject(n) for n in epics]
            if stage.learn_queue is not None and stage.learn_queue.value == "epic"
            else [s.key for s in stories]
        )
        for subject in subjects:
            skipped = i < start
            d["units"].append(
                {
                    "scope": unit_scope(stage, subject),
                    "stage": stage.id,
                    "subject": subject,
                    "status": S.SKIPPED if skipped else S.PENDING,
                    "reason": f"run starts at '{d['from_stage']}'" if skipped else None,
                }
            )


def _worktree_ready(p: Pipeline, d: dict[str, Any], e: WorktreeReady) -> None:
    for s in d["scopes"]:
        if s["id"] == e.scope:
            s["worktree"] = e.worktree
            if s["head_sha"] is None:
                s["head_sha"] = e.head_sha
            return
    d["scopes"].append(
        {"id": e.scope, "branch": e.branch, "worktree": e.worktree, "head_sha": e.head_sha}
    )


def _unit_started(p: Pipeline, d: dict[str, Any], e: UnitStarted) -> None:
    u = _unit(d, e.scope, e.stage)
    _move(u, S.RUNNING, f"unit {e.scope}/{e.stage}")
    u["round"] += 1
    u["reason"] = None
    u["invalidated_by"] = None


def _unit_blocked(p: Pipeline, d: dict[str, Any], e: UnitBlocked) -> None:
    u = _unit(d, e.scope, e.stage)
    _move(u, S.BLOCKED, f"unit {e.scope}/{e.stage}")
    u["reason"] = e.reason


def _unit_unblocked(p: Pipeline, d: dict[str, Any], e: UnitUnblocked) -> None:
    u = _unit(d, e.scope, e.stage)
    _move(u, S.PENDING, f"unit {e.scope}/{e.stage}")
    u["reason"] = None


def _step_started(p: Pipeline, d: dict[str, Any], e: StepStarted) -> None:
    if e.seq != d["next_seq"]:
        raise CoreError(f"step seq {e.seq} does not match next_seq {d['next_seq']}")
    u = _unit(d, e.scope, e.stage)
    if u["status"] != S.RUNNING or u["round"] != e.round:
        raise CoreError(f"unit {e.scope}/{e.stage} is not running round {e.round}")
    step = p.stage(e.stage).step(e.step)
    d["steps"].append(
        {
            "seq": e.seq,
            "scope": e.scope,
            "stage": e.stage,
            "step": e.step,
            "round": e.round,
            "status": S.RUNNING,
            "cap": e.cap,
            "base_sha": _scope(d, e.scope)["head_sha"],
            "started_at": e.at,
            # AD-24: later pipeline edits do not re-attribute a step that already started.
            "agent_role": step.agent_role,
        }
    )
    d["next_seq"] += 1


def _step_finished(p: Pipeline, d: dict[str, Any], e: StepFinished) -> None:
    r = _record(d, e.seq)
    if r["status"] != S.RUNNING:
        raise CoreError(f"step {e.seq} is not running")
    step = p.stage(r["stage"]).step(r["step"])
    verdict = None
    if step.verdict_source is VerdictSource.RESULT_BLOCK:
        verdict = last_verdict_block(e.result.result)
    elif step.verdict_source is VerdictSource.FILE:
        verdict = parse_verdict_text(e.verdict_text)
    readiness = p.stage(r["stage"]).gate_of_step(step.id).kind == "readiness"
    derived = derive_outcome(step, e.result, verdict, readiness=readiness)
    r.update(
        finished_at=e.at,
        outcome=derived.outcome,
        cost=e.result.total_cost_usd,
        reason=derived.reason,
        status_value=derived.status_value,
        verdict=verdict.model_dump() if verdict else None,
    )
    d["budget"]["spent"] += e.result.total_cost_usd  # the reservation is released in `apply`
    what = f"step {e.seq} ({r['scope']}/{r['step']})"
    if derived.outcome is Outcome.LIMITED:
        # AD-18: gates and round counters ignore the attempt; the worktree is reset (AD-6) and
        # a new attempt of the step, in the same round, becomes pending.
        _move(r, S.SUPERSEDED, what)
        sc = _scope(d, r["scope"])
        sc["needs_reset"] = True
        sc["reset_without_feedback"] = True  # the cut-short attempt's diff is not a fix hint
        _limit_wait(p, d, e.at, e.result.resets_at)  # set here, so a crash cannot lose it
    elif derived.outcome is Outcome.DONE:
        _move(r, S.PASSED, what)
        episode = d["limit_episode_start"]
        if episode is not None and r["started_at"] >= episode:
            d["limit_episode_start"] = None  # AD-18: a step started in the episode ended done
        if e.head_sha:
            _scope(d, r["scope"])["head_sha"] = e.head_sha
    elif derived.outcome is Outcome.AMBIGUOUS:
        _move(r, S.ESCALATED, what)
        u = _unit(d, r["scope"], r["stage"])
        if u["status"] == S.RUNNING:  # AD-18: a blocking question escalates at once
            _move(u, S.ESCALATED, f"unit {r['scope']}/{r['stage']}")
            u["reason"] = f"step '{r['step']}': {derived.reason}"
            _scope(d, r["scope"])["needs_reset"] = True
            _escalate_record(d, r["scope"], u["reason"])
    else:
        _move(r, S.FAILED, what)
    # AD-24: no record for a script, or for an attempt that failed before any model ran.
    if is_measured(step.kind) and e.result.subtype not in NO_MODEL_SUBTYPES:
        r["usage"] = _usage_record(p, d, r, e.result, e.at)


def _step_interrupted(p: Pipeline, d: dict[str, Any], e: StepInterrupted) -> None:
    r = _record(d, e.seq)
    _move(r, S.FAILED, f"step {e.seq}")
    r["interrupted"] = True
    r["reason"] = "interrupted; rerun in the same round"
    r["finished_at"] = e.at
    # The real cost of a killed process is unknown: charge its whole cap (AD-11).
    d["budget"]["spent"] += r["cap"]
    r["cost"] = r["cap"]
    kind = _step_kind(p, r)
    if kind is None or is_measured(kind):
        r["usage"] = _usage_record(p, d, r, None, e.at)


# --------------------------------------------------------------------------- usage (AD-24)

INCOMPLETE_SUBTYPES = frozenset({"error_during_execution"})
# The orchestrator failed the step before any `claude` process started (AD-24: no record).
NO_MODEL_SUBTYPES = frozenset({"error_orchestrator"})


def _step_kind(p: Pipeline, r: dict[str, Any]) -> StepKind | None:
    """The step's kind in the current pipeline; None when the pipeline no longer has it."""
    try:
        return p.stage(r["stage"]).step(r["step"]).kind
    except KeyError:
        return None


def _usage_record(
    p: Pipeline,
    d: dict[str, Any],
    r: dict[str, Any],
    result: StepResult | None,
    at: str,
    *,
    backfill: bool = False,
) -> dict[str, Any]:
    """One finished attempt's usage. Without a parsable result (a timeout, crash or interrupt),
    or for `error_during_execution` with zero cost or no `modelUsage`, the record charges what
    the budget was charged, counts zero tokens and sets `usage_complete: false`."""
    role = r.get("agent_role") or UNKNOWN_ROLE
    try:
        stage = p.stage(r["stage"])
        step = stage.step(r["step"])
        role = r.get("agent_role") or step.agent_role or UNKNOWN_ROLE
        requested, effort, _ = stage.limits_for(step, p.settings)
    except KeyError:  # the pipeline no longer has this stage or step
        requested, effort = None, None
    models = result.model_usage if result is not None else ()
    complete = (
        result is not None
        and not backfill
        and bool(models)
        and not (result.subtype in INCOMPLETE_SUBTYPES and result.total_cost_usd == 0)
    )
    if not complete:
        models = ()
    # The ids `--by model` groups by; the configured alias is kept as `model_requested`.
    model = max(models, key=lambda m: m.cost_usd).model if models else requested
    attempt = sum(
        1
        for x in d["steps"]
        if (x["scope"], x["step"], x["round"]) == (r["scope"], r["step"], r["round"])
        and x["seq"] <= r["seq"]
    )
    record = {
        "run_id": d["run_id"],
        "seq": r["seq"],
        "attempt": attempt,
        "scope": r["scope"],
        "stage": r["stage"],
        "step": r["step"],
        "round": r["round"],
        "agent_role": role,
        "model": model,
        "model_requested": requested,
        "effort": str(effort) if effort else None,
        "status": r["status"],
        "outcome": r["outcome"],
        "subtype": result.subtype if result is not None else None,
        "interrupted": bool(r["interrupted"]),
        **sum_models(models),
        "models": [m.model_dump() for m in models],
        "cost_usd": r["cost"],  # what the budget was charged (AD-11)
        "duration_ms": result.duration_ms if result is not None else None,
        "duration_api_ms": result.duration_api_ms if result is not None else None,
        "num_turns": result.num_turns if result is not None else 0,
        "session_id": result.session_id if result is not None else None,
        "usage_complete": complete,
        "at": at,
    }
    return UsageRecord.model_validate(record).model_dump(mode="json")


def backfill_usage(p: Pipeline, state: RunState) -> RunState:
    """AD-24: a state written before AD-24 gets a record for each finished agent or judge step:
    zero tokens, the step's recorded cost, `usage_complete: false`, and the role from the
    current pipeline, or `unknown`. Returns the state unchanged when nothing is missing."""
    d = state.model_dump(mode="json")
    changed = False
    for r in d["steps"]:
        if r["usage"] is not None or r["status"] in (S.RUNNING, S.PENDING):
            continue
        kind = _step_kind(p, r)
        if (kind is not None and not is_measured(kind)) or (kind is None and not r["cap"]):
            continue
        if not r.get("agent_role") and kind is not None:
            r["agent_role"] = p.stage(r["stage"]).step(r["step"]).agent_role
        r["usage"] = _usage_record(
            p, d, r, None, r["finished_at"] or r["started_at"], backfill=True
        )
        changed = True
    return RunState.model_validate(d) if changed else state


def usage_warning_due(p: Pipeline, state: RunState) -> bool:
    """AD-24: spent + reserved reached `usage_warn_ratio` of the budget, and no warning was
    recorded for this budget value yet."""
    b = state.budget
    if b.limit <= 0 or state.usage_warned_limit == b.limit:
        return False
    return b.spent + b.reserved >= p.settings.usage_warn_ratio * b.limit


def _gate_evaluated(p: Pipeline, d: dict[str, Any], e: GateEvaluated) -> None:
    u = _unit(d, e.scope, e.stage)
    what = f"unit {e.scope}/{e.stage}"
    if u["status"] != S.RUNNING or u["round"] != e.round:
        raise CoreError(f"{what} is not running round {e.round}")
    stage = p.stage(e.stage)
    if e.failed_gate is None:
        _move(u, S.PASSED, what)
        u["feedback"] = ()
        if e.stories is not None:
            _load_stories(p, d, e.stories)
        return

    counter = next(
        (c for c in d["counters"] if c["scope"] == e.scope and c["gate"] == e.failed_gate), None
    )
    if counter is None:
        counter = {"scope": e.scope, "gate": e.failed_gate, "fails": 0}
        d["counters"].append(counter)
    counter["fails"] += 1  # AD-11: the round is charged to the gate that failed
    _scope(d, e.scope)["needs_reset"] = True
    _scope(d, e.scope)["reset_without_feedback"] = False
    _move(u, S.FAILED, what)
    max_rounds = stage.max_rounds or p.settings.max_rounds
    if counter["fails"] >= max_rounds:
        _move(u, S.ESCALATED, what)
        u["escalated_gate"] = e.failed_gate
        u["reason"] = f"gate '{e.failed_gate}' failed {counter['fails']} times"
        u["feedback"] = e.feedback
        _escalate_record(d, e.scope, u["reason"])
        return

    _move(u, S.PENDING, what)
    u["reason"] = f"gate '{e.failed_gate}' failed in round {e.round}"
    u["feedback"] = e.feedback
    target = stage.on_fail or stage.id
    if target == stage.id:
        return
    chain = ancestors(p, stage.id)
    marker = f"{stage.id}:{e.failed_gate}"
    for s in p.stages:
        if s.id == stage.id or s.id not in chain:
            continue
        if s.id != target and target not in ancestors(p, s.id):
            continue
        other = next(
            (
                x
                for x in d["units"]
                if x["stage"] == s.id and x["scope"] == unit_scope(s, u.get("subject"))
            ),
            None,
        )
        if other is not None and other["status"] == S.PASSED:
            _move(other, S.PENDING, f"unit {other['scope']}/{s.id}")
            other["invalidated_by"] = marker
            other["tagged"] = None
            if s.id == target:
                other["feedback"] = e.feedback


def _scope_reset(p: Pipeline, d: dict[str, Any], e: ScopeReset) -> None:
    sc = _scope(d, e.scope)
    sc["needs_reset"] = False
    quiet = sc["reset_without_feedback"]
    sc["reset_without_feedback"] = False
    if e.patch and not quiet:
        for u in d["units"]:
            if u["scope"] == e.scope and u["status"] in (S.PENDING, S.RUNNING):
                u["feedback"] = (*u["feedback"], e.patch)


def _pr_opened(p: Pipeline, d: dict[str, Any], e: PrOpened) -> None:
    _scope(d, e.scope)["pr"] = e.number


def _merged(p: Pipeline, d: dict[str, Any], e: Merged) -> None:
    _mark_merged(d, e.scope, e.sha)


def _mark_merged(d: dict[str, Any], scope: str, sha: str) -> None:
    sc = _scope(d, scope)
    if sc["merged_sha"]:
        return
    sc["merged_sha"] = sha
    if not scope.startswith("story:"):
        return
    key = scope.split(":", 1)[1]
    _enqueue(d, f"learn:{key}")
    epic = Story.model_validate(next(s for s in d["stories"] if s["key"] == key)).epic
    keys = [s["key"] for s in d["stories"] if s["key"].split("-", 1)[0] == str(epic)]
    merged = {s["id"] for s in d["scopes"] if s["merged_sha"]}
    if all(f"story:{k}" in merged for k in keys):
        _enqueue(d, f"learn:{epic_subject(epic)}")


def _enqueue(d: dict[str, Any], scope: str) -> None:
    for u in d["units"]:
        if u["scope"] == scope and u["queued"] is None:
            u["queued"] = d["next_learn_seq"]
            d["next_learn_seq"] += 1


def _tagged(p: Pipeline, d: dict[str, Any], e: Tagged) -> None:
    _unit(d, e.scope, e.stage)["tagged"] = e.tag


def _escalation_opened(p: Pipeline, d: dict[str, Any], e: EscalationOpened) -> None:
    for x in d["escalations"]:
        if x["scope"] == e.scope:
            x["issue"] = e.issue
            return
    raise CoreError(f"no escalation for {e.scope}")


def _usage_limited(p: Pipeline, d: dict[str, Any], e: UsageLimited) -> None:
    """Audit of a limited step. `StepFinished` already set the wait; re-applying it from the
    record's own finish time changes nothing (idempotent)."""
    r = _record(d, e.seq)
    if r["status"] != S.SUPERSEDED:
        raise CoreError(f"step {e.seq} is not superseded by a usage limit")
    _limit_wait(p, d, r["finished_at"] or e.at, e.resets_at)


def _limit_wait(p: Pipeline, d: dict[str, Any], at: str, resets_at: str | None) -> None:
    """AD-18: resume_at = max(current, the named reset time, now + usage_limit_wait_min)."""
    settings = p.settings
    candidates = [parse_time(at) + timedelta(minutes=settings.usage_limit_wait_min)]
    if d["resume_at"]:
        candidates.append(parse_time(d["resume_at"]))
    if resets_at:
        candidates.append(parse_time(resets_at))
    resume_at = max(candidates)
    d["resume_at"] = format_time(resume_at)
    if d["limit_episode_start"] is None:
        d["limit_episode_start"] = at
    start = parse_time(d["limit_episode_start"])
    # The episode lasts at least until resume_at: escalate once that passes the maximum wait.
    if not d["limit_exceeded"] and resume_at - start > timedelta(
        hours=settings.usage_limit_max_wait_h
    ):
        d["limit_exceeded"] = True
        _escalate_record(
            d,
            "run",
            f"plan usage limit: still limited after {settings.usage_limit_max_wait_h:g} h "
            f"(episode since {d['limit_episode_start']}, next try {d['resume_at']})",
        )


def _paths_reverted(p: Pipeline, d: dict[str, Any], e: PathsReverted) -> None:
    """Audit only: the commit put these paths back first (AD-24, ADR 0021)."""


def _proposal(d: dict[str, Any], sha256: str) -> dict[str, Any]:
    for x in d["proposals"]:
        if x["sha256"] == sha256:
            return x
    raise CoreError(f"no proposal {sha256[:12]}")


def _proposal_captured(p: Pipeline, d: dict[str, Any], e: ProposalCaptured) -> None:
    """AD-26: one record per patch; the same patch in a later round or after a resume adds
    nothing."""
    if any(x["sha256"] == e.sha256 for x in d["proposals"]):
        return
    d["proposals"].append(
        {
            "sha256": e.sha256,
            "scope": e.scope,
            "seq": e.seq,
            "agent_role": e.agent_role,
            "paths": list(e.paths),
            "patch": e.patch,
            "review": e.review,
        }
    )


def _proposal_delivered(p: Pipeline, d: dict[str, Any], e: ProposalDelivered) -> None:
    x = _proposal(d, e.sha256)
    x.update(status=ProposalStatus.DELIVERED, kind=e.kind, number=e.number, reason=e.reason)


def _proposal_failed(p: Pipeline, d: dict[str, Any], e: ProposalFailed) -> None:
    x = _proposal(d, e.sha256)
    if x["status"] == ProposalStatus.DELIVERED:
        raise CoreError(f"proposal {e.sha256[:12]} is already delivered")
    x.update(status=ProposalStatus.FAILED, reason=e.reason)


def _owner_review_awaited(p: Pipeline, d: dict[str, Any], e: OwnerReviewAwaited) -> None:
    """AD-27: the unit waits for the owner, blocked; no round is consumed and no later step of
    the round (CI, merge) runs."""
    u = _unit(d, e.scope, e.stage)
    _move(u, S.BLOCKED, f"unit {e.scope}/{e.stage}")
    u["reason"] = f"awaiting owner review of PR #{e.pr}"
    if not any(a["scope"] == e.scope and a["stage"] == e.stage for a in d["awaiting"]):
        d["awaiting"].append(
            {"scope": e.scope, "stage": e.stage, "pr": e.pr, "since": e.at, "head": e.head}
        )


def _awaited_settled(p: Pipeline, d: dict[str, Any], e: AwaitedSettled) -> None:
    """AD-27: merged, the unit passes (the `Merged` event that follows queues its learn
    unit); closed, or merged with another head, the unit escalates. A run that only waited
    for the owner escalates once nothing is awaited any more."""
    found = [a for a in d["awaiting"] if a["scope"] == e.scope and a["stage"] == e.stage]
    if not found:
        raise CoreError(f"nothing awaited for {e.scope}/{e.stage}")
    d["awaiting"] = [a for a in d["awaiting"] if a not in found]
    u = _unit(d, e.scope, e.stage)
    what = f"unit {e.scope}/{e.stage}"
    if u["status"] == S.BLOCKED:
        _move(u, S.PENDING, what)
    if u["status"] == S.PENDING:
        _move(u, S.RUNNING, what)
    if e.merged:
        if not e.sha:
            raise CoreError(f"PR #{e.pr} merged without a SHA")
        _move(u, S.PASSED, what)
        u["reason"] = f"PR #{e.pr} merged by the owner"
        return
    _move(u, S.ESCALATED, what)
    u["reason"] = e.reason or f"PR #{e.pr} closed by the owner without merging"
    _escalate_record(d, e.scope, u["reason"])
    if Status(d["status"]) is S.AWAITING_OWNER and not d["awaiting"]:
        _move(d, S.ESCALATED, "run")
        d["exit_code"] = 2


def _intake_recorded(p: Pipeline, d: dict[str, Any], e: IntakeRecorded) -> None:
    d["issue"] = e.intake.model_dump(mode="json")


def _intake_rejected(p: Pipeline, d: dict[str, Any], e: IntakeRejected) -> None:
    """AD-27: sticky: a resume re-checks the issue but never clears this."""
    if d["intake_rejected"] is None:
        d["intake_rejected"] = e.reason
    elif e.reason not in d["intake_rejected"]:
        d["intake_rejected"] = f"{d['intake_rejected']}; then: {e.reason}"
    scope = f"story:{d['stories'][0]['key']}" if d["stories"] else "run"
    _escalate_record(d, scope, f"issue intake: {e.reason}")


def _usage_warning(p: Pipeline, d: dict[str, Any], e: UsageWarning) -> None:
    d["usage_warned_limit"] = e.budget_limit


def _budget_exhausted(p: Pipeline, d: dict[str, Any], e: BudgetExhausted) -> None:
    d["budget"]["exhausted"] = True
    _escalate_record(d, "run", e.reason)


def _run_finished(p: Pipeline, d: dict[str, Any], e: RunFinished) -> None:
    if e.exit_code == 0:
        new = S.PASSED
    elif (
        d["budget"]["exhausted"]
        or d["limit_exceeded"]
        or d["intake_rejected"] is not None
        or any(u["status"] == S.ESCALATED for u in d["units"])
    ):
        new = S.ESCALATED
    elif d["awaiting"] and not any(
        u["status"] == S.BLOCKED
        and not any((a["scope"], a["stage"]) == (u["scope"], u["stage"]) for a in d["awaiting"])
        for u in d["units"]
    ):
        new = S.AWAITING_OWNER  # AD-27: only owner-review waits remain
    else:
        new = S.BLOCKED
    _move(d, new, "run")
    d["exit_code"] = e.exit_code


def _pause_requested(p: Pipeline, d: dict[str, Any], e: PauseRequested) -> None:
    if d["pause_requested_at"] is None:
        d["pause_kind"] = "pause"
        d["pause_requested_at"] = e.at


def _run_paused(p: Pipeline, d: dict[str, Any], e: RunPaused) -> None:
    """Issue #72: no round is consumed; the steps that ran are already recorded."""
    if any(r["status"] == S.RUNNING for r in d["steps"]):
        raise CoreError("cannot pause the run while a step is still running")
    _move(d, S.PAUSED, "run")
    d["pause_kind"] = e.kind
    d["pause_requested_at"] = d["pause_requested_at"] or e.at
    d["paused_at"] = e.at
    d["exit_code"] = 0


def _run_stopped(p: Pipeline, d: dict[str, Any], e: RunStopped) -> None:
    """Audit only: a resume picks the run up from the state as it is."""


_HANDLERS: dict[type, Any] = {
    RunStarted: _run_started,
    RunResumed: _run_resumed,
    StoriesLoaded: _stories_loaded,
    WorktreeReady: _worktree_ready,
    UnitStarted: _unit_started,
    UnitBlocked: _unit_blocked,
    UnitUnblocked: _unit_unblocked,
    StepStarted: _step_started,
    StepFinished: _step_finished,
    StepInterrupted: _step_interrupted,
    GateEvaluated: _gate_evaluated,
    ScopeReset: _scope_reset,
    PrOpened: _pr_opened,
    Merged: _merged,
    Tagged: _tagged,
    EscalationOpened: _escalation_opened,
    UsageLimited: _usage_limited,
    UsageWarning: _usage_warning,
    PathsReverted: _paths_reverted,
    ProposalCaptured: _proposal_captured,
    ProposalDelivered: _proposal_delivered,
    ProposalFailed: _proposal_failed,
    OwnerReviewAwaited: _owner_review_awaited,
    AwaitedSettled: _awaited_settled,
    IntakeRecorded: _intake_recorded,
    IntakeRejected: _intake_rejected,
    BudgetExhausted: _budget_exhausted,
    PauseRequested: _pause_requested,
    RunPaused: _run_paused,
    RunStopped: _run_stopped,
    RunFinished: _run_finished,
}
