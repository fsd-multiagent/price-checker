"""Frozen data models shared by the core, the app and the adapters.

Every model is a frozen pydantic v2 model with tuple fields. A new value is built with
`model_validate`, never with `model_copy(update=...)` (spine "Consistency Conventions").
"""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field


class Frozen(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


# --------------------------------------------------------------------------- enums


class Status(StrEnum):
    """The closed status set of AD-5."""

    PENDING = "pending"
    RUNNING = "running"
    PASSED = "passed"
    FAILED = "failed"
    BLOCKED = "blocked"
    ESCALATED = "escalated"
    SKIPPED = "skipped"
    SUPERSEDED = "superseded"  # step records only: an attempt cut short by a plan limit (AD-18)


class StepKind(StrEnum):
    AGENT = "agent"
    JUDGE = "judge"
    SCRIPT = "script"


class Outcome(StrEnum):
    """AD-18: only `done` lets a step pass."""

    DONE = "done"
    BLOCKED = "blocked"
    AMBIGUOUS = "ambiguous"
    ERROR = "error"
    LIMITED = "limited"  # a plan usage limit was hit (AD-18)


class Effort(StrEnum):
    """AD-7: `--effort` levels. `ultracode` is excluded on purpose: it fans out many agents."""

    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    XHIGH = "xhigh"
    MAX = "max"


class OutcomeSource(StrEnum):
    SUBTYPE = "subtype"
    RESULT_STATUS = "result_status"
    VERDICT = "verdict"
    EXIT_CODE = "exit_code"


class VerdictSource(StrEnum):
    RESULT_BLOCK = "result_block"
    FILE = "file"


class RunKind(StrEnum):
    FULL = "full"
    STORY = "story"
    FIX = "fix"


class LearnQueue(StrEnum):
    STORY = "story"
    EPIC = "epic"


# --------------------------------------------------------------------------- pipeline


class Step(Frozen):
    id: str
    kind: StepKind
    skill: str | None = None
    agent: str | None = None
    script: str | None = None
    prompt: str | None = None
    tools: tuple[str, ...] = ()
    inputs: tuple[str, ...] = ()
    writes: bool = False
    outcome_source: OutcomeSource | None = None
    verdict_source: VerdictSource | None = None


class Gate(Frozen):
    kind: str
    steps: tuple[str, ...] = ()


class Stage(Frozen):
    id: str
    title: str = ""
    entry: tuple[RunKind, ...]
    per_story: bool = False
    learn_queue: LearnQueue | None = None
    needs: tuple[str, ...] | None = None
    branch: str | None = None
    steps: tuple[Step, ...]
    parallel: tuple[tuple[str, ...], ...] = ()
    gate: tuple[Gate, ...]
    on_fail: str | None = None
    max_rounds: int | None = None
    timeout_min: int | None = None
    budget_usd: float = 0.0
    model: str | None = None
    effort: Effort | None = None
    max_turns: int | None = None
    stories_file: str | None = None

    def step(self, step_id: str) -> Step:
        for s in self.steps:
            if s.id == step_id:
                return s
        raise KeyError(f"stage '{self.id}' has no step '{step_id}'")

    def phases(self) -> tuple[tuple[Step, ...], ...]:
        """Steps grouped into the order they run in; a group's steps run in parallel."""
        group_of = {sid: g for g in self.parallel for sid in g}
        out: list[tuple[Step, ...]] = []
        seen: set[str] = set()
        for s in self.steps:
            if s.id in seen:
                continue
            group = group_of.get(s.id, (s.id,))
            out.append(tuple(self.step(g) for g in group))
            seen.update(group)
        return tuple(out)

    def gate_steps(self, gate: Gate) -> tuple[Step, ...]:
        """A gate's steps. A gate without `steps` takes the steps no other gate claims."""
        if gate.steps:
            return tuple(self.step(g) for g in gate.steps)
        claimed = {sid for g in self.gate for sid in g.steps}
        return tuple(s for s in self.steps if s.id not in claimed)

    def gate_of_step(self, step_id: str) -> Gate:
        for g in self.gate:
            if step_id in g.steps:
                return g
        return next((g for g in self.gate if not g.steps), self.gate[0])

    @property
    def family(self) -> str:
        """Which kind of scope the stage runs on: run, story or learn."""
        if self.learn_queue is not None:
            return "learn"
        return "story" if self.per_story else "run"


class Settings(Frozen):
    max_rounds: int = 3
    max_parallel: int = 3
    timeout_min: int = 30
    serial_paths: tuple[str, ...] = ("api/openapi.yaml",)
    bookkeeping_paths: tuple[str, ...] = (
        "_bmad-output/implementation-artifacts/sprint-status.yaml",
        "_bmad-output/implementation-artifacts/deferred-work*",
        "docs/adr/README.md",
        "docs/*-register.md",
        "CLAUDE.md",
        "AGENTS.md",
    )
    ci_timeout_min: int = 30
    run_budget_usd: float
    register_path: str = "docs/source-register.md"
    default_model: str | None = None
    default_effort: Effort | None = None
    usage_limit_wait_min: float = Field(default=30, gt=0)
    usage_limit_max_wait_h: float = Field(default=12, gt=0)
    allow_api_key: bool = False


class Pipeline(Frozen):
    settings: Settings
    stages: tuple[Stage, ...]

    def stage(self, stage_id: str) -> Stage:
        for s in self.stages:
            if s.id == stage_id:
                return s
        raise KeyError(f"unknown stage '{stage_id}'")

    def index(self, stage_id: str) -> int:
        return [s.id for s in self.stages].index(stage_id)


# --------------------------------------------------------------------------- verdicts


class Severity(StrEnum):
    BLOCKER = "blocker"
    MAJOR = "major"
    MINOR = "minor"


class Finding(Frozen):
    """One finding, field for field as in docs/standards/review-findings.md."""

    model_config = ConfigDict(frozen=True, extra="ignore")

    id: str
    severity: Severity
    rule: str | None = None
    file: str | None = None
    line: int | None = None
    summary: str
    fix: str | None = None


class Verdict(Frozen):
    model_config = ConfigDict(frozen=True, extra="ignore")

    reviewer: str
    target: str
    verdict: Literal["pass", "fail"]
    findings: tuple[Finding, ...] = ()

    @property
    def passed(self) -> bool:
        return self.verdict == "pass"


# --------------------------------------------------------------------------- step results


class Denial(Frozen):
    tool_name: str
    detail: str = ""


class StepResult(Frozen):
    """One step's result. Agent steps copy the `claude -p --output-format json` shape (AD-8)."""

    subtype: str = "success"
    is_error: bool = False
    total_cost_usd: float = 0.0
    num_turns: int = 0
    permission_denials: tuple[Denial, ...] = ()
    result: str | None = None
    errors: tuple[str, ...] = ()
    exit_code: int | None = None
    # Set by `adapters/claude.py` when the error text names a plan usage limit (AD-18).
    usage_limited: bool = False
    resets_at: str | None = None  # the reset time the limit message names, if any (UTC ISO)


# --------------------------------------------------------------------------- stories


class Story(Frozen):
    key: str
    title: str
    depends_on: tuple[str, ...] = ()
    touches: tuple[str, ...] = ()
    sources: tuple[str, ...]

    @property
    def epic(self) -> int:
        return int(self.key.split("-", 1)[0])


# --------------------------------------------------------------------------- run state


class Unit(Frozen):
    """One stage running on one scope. Its status follows AD-5."""

    scope: str
    stage: str
    subject: str | None = None
    status: Status = Status.PENDING
    round: int = 0
    reason: str | None = None
    invalidated_by: str | None = None
    escalated_gate: str | None = None
    feedback: tuple[str, ...] = ()
    tagged: str | None = None
    queued: int | None = None


class Scope(Frozen):
    id: str
    branch: str
    worktree: str | None = None
    head_sha: str | None = None
    needs_reset: bool = False
    reset_without_feedback: bool = False  # the reset discards a superseded attempt (AD-18)
    pr: int | None = None
    merged_sha: str | None = None


class StepRecord(Frozen):
    seq: int
    scope: str
    stage: str
    step: str
    round: int
    status: Status
    cap: float
    base_sha: str | None = None
    started_at: str
    finished_at: str | None = None
    outcome: Outcome | None = None
    cost: float = 0.0
    reason: str | None = None
    status_value: str | None = None
    verdict: Verdict | None = None
    interrupted: bool = False


class Counter(Frozen):
    scope: str
    gate: str
    fails: int = 0


class Escalation(Frozen):
    scope: str
    reason: str
    issue: int | None = None


class Budget(Frozen):
    limit: float
    spent: float = 0.0
    reserved: float = 0.0
    exhausted: bool = False


class RunState(Frozen):
    run_id: str
    kind: RunKind
    dry_run: bool
    created_at: str
    from_stage: str | None = None
    status: Status = Status.PENDING
    exit_code: int | None = None
    budget: Budget
    units: tuple[Unit, ...] = ()
    scopes: tuple[Scope, ...] = ()
    steps: tuple[StepRecord, ...] = ()
    counters: tuple[Counter, ...] = ()
    stories: tuple[Story, ...] = ()
    stories_loaded: bool = False
    escalations: tuple[Escalation, ...] = ()
    next_seq: int = 1
    next_learn_seq: int = 1
    fixtures: str | None = None
    scenario: str | None = None
    pipeline_path: str | None = None
    pipeline_sha256: str | None = None
    # AD-18: no step starts before `resume_at`; the episode runs from the first `limited`
    # result to the next step that ends `done`. `limit_exceeded` escalates the run (scope run).
    resume_at: str | None = None
    limit_episode_start: str | None = None
    limit_exceeded: bool = False

    @property
    def halted(self) -> bool:
        """No new step starts: the budget is exhausted or a limit episode ran too long."""
        return self.budget.exhausted or self.limit_exceeded

    def unit(self, scope: str, stage: str) -> Unit | None:
        for u in self.units:
            if u.scope == scope and u.stage == stage:
                return u
        return None

    def scope(self, scope_id: str) -> Scope | None:
        for s in self.scopes:
            if s.id == scope_id:
                return s
        return None

    def story(self, key: str) -> Story | None:
        for s in self.stories:
            if s.key == key:
                return s
        return None

    def step_record(self, seq: int) -> StepRecord:
        for r in self.steps:
            if r.seq == seq:
                return r
        raise KeyError(f"no step record {seq}")

    def counter(self, scope: str, gate: str) -> int:
        for c in self.counters:
            if c.scope == scope and c.gate == gate:
                return c.fails
        return 0

    def escalation(self, scope: str) -> Escalation | None:
        for e in self.escalations:
            if e.scope == scope:
                return e
        return None


# --------------------------------------------------------------------------- events


class _Event(Frozen):
    at: str


class RunStarted(_Event):
    type: Literal["run_started"] = "run_started"


class RunResumed(_Event):
    type: Literal["run_resumed"] = "run_resumed"
    budget_limit: float | None = None  # the current run_budget_usd, re-read on resume


class StoriesLoaded(_Event):
    type: Literal["stories_loaded"] = "stories_loaded"
    stories: tuple[Story, ...]


class WorktreeReady(_Event):
    type: Literal["worktree_ready"] = "worktree_ready"
    scope: str
    branch: str
    worktree: str
    head_sha: str


class UnitStarted(_Event):
    type: Literal["unit_started"] = "unit_started"
    scope: str
    stage: str


class UnitBlocked(_Event):
    type: Literal["unit_blocked"] = "unit_blocked"
    scope: str
    stage: str
    reason: str


class UnitUnblocked(_Event):
    type: Literal["unit_unblocked"] = "unit_unblocked"
    scope: str
    stage: str


class StepStarted(_Event):
    type: Literal["step_started"] = "step_started"
    seq: int
    scope: str
    stage: str
    step: str
    round: int
    cap: float


class StepFinished(_Event):
    type: Literal["step_finished"] = "step_finished"
    seq: int
    result: StepResult
    verdict_text: str | None = None
    head_sha: str | None = None


class StepInterrupted(_Event):
    type: Literal["step_interrupted"] = "step_interrupted"
    seq: int


class GateEvaluated(_Event):
    type: Literal["gate_evaluated"] = "gate_evaluated"
    scope: str
    stage: str
    round: int
    verdicts: tuple[tuple[str, Verdict], ...]
    failed_gate: str | None = None
    feedback: tuple[str, ...] = ()
    stories: tuple[Story, ...] | None = None


class ScopeReset(_Event):
    type: Literal["scope_reset"] = "scope_reset"
    scope: str
    patch: str | None = None


class PrOpened(_Event):
    type: Literal["pr_opened"] = "pr_opened"
    scope: str
    number: int


class Merged(_Event):
    type: Literal["merged"] = "merged"
    scope: str
    sha: str


class Tagged(_Event):
    type: Literal["tagged"] = "tagged"
    scope: str
    stage: str
    tag: str


class EscalationOpened(_Event):
    type: Literal["escalation_opened"] = "escalation_opened"
    scope: str
    issue: int


class UsageLimited(_Event):
    """AD-18: step `seq` hit a plan usage limit; the run waits until `resume_at`."""

    type: Literal["usage_limited"] = "usage_limited"
    seq: int
    resets_at: str | None = None


class BudgetExhausted(_Event):
    type: Literal["budget_exhausted"] = "budget_exhausted"
    reason: str


class RunStopped(_Event):
    """The loop stopped on a fatal error; audit only, the state stays as it was."""

    type: Literal["run_stopped"] = "run_stopped"
    reason: str


class RunFinished(_Event):
    type: Literal["run_finished"] = "run_finished"
    exit_code: int


Event = Annotated[
    RunStarted
    | RunResumed
    | StoriesLoaded
    | WorktreeReady
    | UnitStarted
    | UnitBlocked
    | UnitUnblocked
    | StepStarted
    | StepFinished
    | StepInterrupted
    | GateEvaluated
    | ScopeReset
    | PrOpened
    | Merged
    | Tagged
    | EscalationOpened
    | UsageLimited
    | BudgetExhausted
    | RunStopped
    | RunFinished,
    Field(discriminator="type"),
]


# --------------------------------------------------------------------------- actions


class StartUnit(Frozen):
    type: Literal["start_unit"] = "start_unit"
    scope: str
    stage: str
    branch: str


class StartStep(Frozen):
    type: Literal["start_step"] = "start_step"
    scope: str
    stage: str
    step: str
    round: int
    cap: float


class EvaluateGate(Frozen):
    type: Literal["evaluate_gate"] = "evaluate_gate"
    scope: str
    stage: str
    round: int


class ResetScope(Frozen):
    type: Literal["reset_scope"] = "reset_scope"
    scope: str


class BlockUnit(Frozen):
    type: Literal["block_unit"] = "block_unit"
    scope: str
    stage: str
    reason: str


class UnblockUnit(Frozen):
    type: Literal["unblock_unit"] = "unblock_unit"
    scope: str
    stage: str


class OpenEscalation(Frozen):
    type: Literal["open_escalation"] = "open_escalation"
    scope: str
    reason: str


class TagStage(Frozen):
    type: Literal["tag_stage"] = "tag_stage"
    scope: str
    stage: str
    tag: str


class ExhaustBudget(Frozen):
    type: Literal["exhaust_budget"] = "exhaust_budget"
    reason: str


class Wait(Frozen):
    """AD-1, AD-11: nothing can start before `until`; the app sleeps through the clock port."""

    type: Literal["wait"] = "wait"
    until: str


class Finish(Frozen):
    type: Literal["finish"] = "finish"
    exit_code: int


Action = (
    StartUnit
    | StartStep
    | EvaluateGate
    | ResetScope
    | BlockUnit
    | UnblockUnit
    | OpenEscalation
    | TagStage
    | ExhaustBudget
    | Wait
    | Finish
)


class TickInputs(Frozen):
    """Facts read from outside the core before each decision (AD-1)."""

    approved_domains: frozenset[str] = frozenset()
    now: str | None = None  # UTC ISO from the clock port; None means "no time-based waits"


class CoreError(Exception):
    """An illegal transition or an event that does not fit the state."""
