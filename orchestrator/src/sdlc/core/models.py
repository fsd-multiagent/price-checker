"""Frozen data models shared by the core, the app and the adapters.

Every model is a frozen pydantic v2 model with tuple fields. A new value is built with
`model_validate`, never with `model_copy(update=...)` (spine "Consistency Conventions").
"""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Any, Literal, NamedTuple

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
    # The run only (AD-27): only owner-review waits remain; a later command picks it up.
    AWAITING_OWNER = "awaiting_owner"
    # The run only (issue #72): the owner paused or stopped it; `sdlc resume` continues it.
    PAUSED = "paused"


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
    # AD-24: the role, in Name (Position) form, charged with the step's usage. Required on
    # agent and judge steps; left out of the stage-table digest.
    agent_role: str | None = None
    # AD-7: optional per-step overrides of the stage's model, effort and max_turns. Left out of
    # the stage-table digest, like the stage limits, so tuning them does not block a resume.
    model: str | None = None
    effort: Effort | None = None
    max_turns: int | None = None

    @property
    def runner(self) -> str | None:
        """What the step runs, for the role check: `judge` for every judge step, the skill or
        agent of an agent step, None for a script."""
        if self.kind is StepKind.SCRIPT:
            return None
        if self.kind is StepKind.JUDGE:
            return "judge"
        return self.skill or self.agent


class StepLimits(NamedTuple):
    """A step's resolved `--model`, `--effort` and `--max-turns` (AD-7)."""

    model: str | None
    effort: Effort | None
    max_turns: int | None


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

    def limits_for(self, step: Step, settings: Settings) -> StepLimits:
        """AD-7: the step's value, else the stage's, else the settings default (model and
        effort); max_turns has no settings default."""
        return StepLimits(
            model=step.model or self.model or settings.default_model,
            effort=step.effort or self.effort or settings.default_effort,
            max_turns=step.max_turns or self.max_turns,
        )

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


OTLP_PROTOCOLS = ("grpc", "http/protobuf", "http/json")


class Telemetry(Frozen):
    """AD-25: opt-in OpenTelemetry metrics export over OTLP, the only exporter. Collector
    headers come from the runner's environment, never from here (AD-23)."""

    enabled: bool = False
    endpoint: str | None = None
    protocol: str | None = None


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
        # AD-21 (AD-27): parallel stories of one epic must not carry these.
        "_bmad-output/implementation-artifacts/epic-*-context.md",
        "_bmad-output/planning-artifacts/architecture/**/ARCHITECTURE-SPINE.md",
    )
    ci_timeout_min: int = 30
    run_budget_usd: float
    register_path: str = "docs/source-register.md"
    default_model: str | None = None
    default_effort: Effort | None = None
    usage_limit_wait_min: float = Field(default=30, gt=0)
    usage_limit_max_wait_h: float = Field(default=12, gt=0)
    allow_api_key: bool = False
    # AD-24: role (Name (Position)) -> the skills and agent definitions it may run; `judge`
    # stands for every judge step.
    agents: dict[str, tuple[str, ...]] = Field(default_factory=dict)
    usage_warn_ratio: float = Field(default=0.8, gt=0, le=1)
    telemetry: Telemetry = Telemetry()
    # AD-24: paths a learn branch reverts before it commits, so the pipeline changes only
    # through an owner-reviewed PR.
    learn_revert_paths: tuple[str, ...] = ()
    # AD-26: three path tiers, each entry a path prefix (ending in "/") or an exact file.
    # Owner-only paths are reverted before every commit and never proposed; protected paths
    # become owner-review proposals, then are reverted (the `sdlc-commands` block of AGENTS.md
    # is always protected); review paths (workflows) are never pushed from a proposal.
    owner_only_paths: tuple[str, ...] = (
        ".claude/",
        ".github/CODEOWNERS",
        "docs/source-register.md",
    )
    protected_paths: tuple[str, ...] = (
        "sdlc/",
        "orchestrator/",
        "_bmad/custom/",
        "docs/standards/",
    )
    review_paths: tuple[str, ...] = (".github/workflows/",)
    # AD-27: the GitHub logins allowed to authorise an issue run (author, last editor and the
    # `factory` label). Empty: no issue run is ever authorised.
    owners: tuple[str, ...] = ()


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


class Tokens(Frozen):
    """The four token counts of `claude -p` usage (AD-24)."""

    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_input_tokens: int = 0
    cache_creation_input_tokens: int = 0


class ModelUsage(Tokens):
    """One model's entry of `modelUsage`; subagents included (AD-24)."""

    model: str
    cost_usd: float = 0.0


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
    # AD-24: token totals come from `model_usage` (subagents included); `usage` covers only the
    # main loop and is kept for information.
    model_usage: tuple[ModelUsage, ...] = ()
    usage: Tokens | None = None
    duration_ms: int | None = None
    duration_api_ms: int | None = None
    session_id: str | None = None
    # The parsed JSON without its `result` text. Kept only in the step file (AD-24): excluded
    # from every dump, so it never reaches `events.jsonl` or `state.json`.
    raw: dict[str, Any] | None = Field(default=None, exclude=True)


# --------------------------------------------------------------------------- stories


class Story(Frozen):
    key: str
    title: str
    depends_on: tuple[str, ...] = ()
    touches: tuple[str, ...] = ()
    sources: tuple[str, ...]
    # AD-27: an issue story's acceptance criteria. Untrusted text: it reaches prompts only
    # inside the fenced "Untrusted issue text" section.
    acceptance: tuple[str, ...] = ()

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


class UsageRecord(Tokens):
    """AD-24: one finished attempt of an agent or judge step; a ledger row. No free text.

    `cost_usd` is the amount charged to the budget. Unknown fields are ignored when read.
    """

    model_config = ConfigDict(frozen=True, extra="ignore")

    v: int = 1
    run_id: str
    seq: int
    attempt: int = 1
    scope: str
    stage: str
    step: str
    round: int
    agent_role: str
    model: str | None = None  # the costliest `modelUsage` id, else the requested model
    model_requested: str | None = None  # the configured `--model` value (an alias), if any
    effort: str | None = None
    status: Status
    outcome: Outcome | None = None
    subtype: str | None = None
    interrupted: bool = False
    models: tuple[ModelUsage, ...] = ()
    cost_usd: float = 0.0
    duration_ms: int | None = None
    duration_api_ms: int | None = None
    num_turns: int = 0
    session_id: str | None = None
    usage_complete: bool = False
    at: str

    @property
    def key(self) -> tuple[str, int]:
        return (self.run_id, self.seq)


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
    agent_role: str | None = None  # copied at StepStarted (AD-24)
    usage: UsageRecord | None = None  # set when an agent or judge attempt ends (AD-24)


class Counter(Frozen):
    scope: str
    gate: str
    fails: int = 0


class Escalation(Frozen):
    scope: str
    reason: str
    issue: int | None = None


class ProposalStatus(StrEnum):
    """AD-26: a proposal waits for delivery, is delivered as a PR or an issue, or failed to be
    delivered (retried on `sdlc resume`). Not a node status of AD-5: proposals never block."""

    PENDING = "pending"
    DELIVERED = "delivered"
    FAILED = "failed"


class Proposal(Frozen):
    """AD-26: a factory change an agent made on its branch, captured before the revert."""

    sha256: str  # of the patch text: the key, and the marker in the PR or issue body
    scope: str
    seq: int
    agent_role: str | None = None
    paths: tuple[str, ...]
    patch: str  # run-dir-relative path of `steps/<seq>.proposal.patch`
    review: bool = False  # touches review_paths: delivered as an issue, never pushed
    status: ProposalStatus = ProposalStatus.PENDING
    kind: Literal["pr", "issue"] | None = None
    number: int | None = None
    reason: str | None = None  # why it became an issue, or why delivery failed

    @property
    def branch(self) -> str:
        return f"proposal/{self.sha256[:8]}"


class Awaiting(Frozen):
    """AD-27: a unit whose PR waits for the owner's review. It is blocked, consumes no round,
    and is never rebased; a later command settles it when the PR is merged or closed."""

    scope: str
    stage: str
    pr: int
    since: str
    head: str | None = None  # the SHA pushed for review; a merge of another head escalates


class IssueIntake(Frozen):
    """AD-27: what authorised an issue run, re-checked by `sdlc resume`. The same snapshot is
    written to the run's `stories.yaml`."""

    issue: int
    title_sha256: str
    body_sha256: str
    author: str
    editor: str | None = None  # the last editor, when the body was edited
    factory: bool = False
    label_event_id: str | None = None  # the `labeled factory` timeline event (factory only)
    label_actor: str | None = None


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
    proposals: tuple[Proposal, ...] = ()  # AD-26
    awaiting: tuple[Awaiting, ...] = ()  # AD-27: PRs waiting for the owner
    issue: IssueIntake | None = None  # AD-27: the issue a `story` or `fix` run came from
    intake_rejected: str | None = None  # AD-27: the issue failed intake or its re-check
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
    # AD-24: the budget value the usage warning was last recorded for.
    usage_warned_limit: float | None = None
    # Issue #72: an owner pause (`pause`) or stop (`stop`). `pause_requested_at` is set while
    # the run finishes its running steps; `paused_at` once it is `paused`.
    pause_kind: Literal["pause", "stop"] | None = None
    pause_requested_at: str | None = None
    paused_at: str | None = None

    @property
    def halted(self) -> bool:
        """No new step starts: the budget is exhausted, a limit episode ran too long, or the
        issue the run came from failed its check (AD-27)."""
        return self.budget.exhausted or self.limit_exceeded or self.intake_rejected is not None

    @property
    def factory(self) -> bool:
        """AD-27: a factory story, authorised by an owner-applied `factory` label."""
        return self.issue is not None and self.issue.factory

    def awaited(self, scope: str, stage: str | None = None) -> Awaiting | None:
        for a in self.awaiting:
            if a.scope == scope and (stage is None or a.stage == stage):
                return a
        return None

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

    def proposal(self, sha256: str) -> Proposal | None:
        for x in self.proposals:
            if x.sha256 == sha256:
                return x
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


class UsageWarning(_Event):
    """AD-24: spent + reserved reached `usage_warn_ratio` of the run budget `budget_limit`."""

    type: Literal["usage_warning"] = "usage_warning"
    budget_limit: float
    spent: float
    reserved: float


class PathsReverted(_Event):
    """Audit: real paths put back before committing step `seq` on an agent branch: learn
    branches revert `learn_revert_paths` (AD-24), and every non-story scope restores the
    `sdlc-commands` block of `AGENTS.md` (ADR 0021)."""

    type: Literal["paths_reverted"] = "paths_reverted"
    scope: str
    seq: int
    paths: tuple[str, ...]


class ProposalCaptured(_Event):
    """AD-26: step `seq` changed protected paths; the patch was saved before the revert. A
    patch already recorded (same SHA-256) adds nothing."""

    type: Literal["proposal_captured"] = "proposal_captured"
    sha256: str
    scope: str
    seq: int
    agent_role: str | None = None
    paths: tuple[str, ...]
    patch: str
    review: bool = False


class ProposalDelivered(_Event):
    """AD-26: the proposal's PR or issue exists (created, or found by its marker)."""

    type: Literal["proposal_delivered"] = "proposal_delivered"
    sha256: str
    kind: Literal["pr", "issue"]
    number: int
    reason: str | None = None


class ProposalFailed(_Event):
    """AD-26: delivery failed; the run goes on, and `sdlc resume` retries it."""

    type: Literal["proposal_failed"] = "proposal_failed"
    sha256: str
    reason: str


class OwnerReviewAwaited(_Event):
    """AD-27: the unit's PR touches protected paths (a factory story) or review paths; it is
    labelled owner-review and the unit is blocked until the owner merges or closes it."""

    type: Literal["owner_review_awaited"] = "owner_review_awaited"
    scope: str
    stage: str
    pr: int
    head: str | None = None


class AwaitedSettled(_Event):
    """AD-27: the owner merged the reviewed head (`merged`; a `Merged` event follows), or
    the PR was closed or merged with another head (`reason`): the unit escalates."""

    type: Literal["awaited_settled"] = "awaited_settled"
    scope: str
    stage: str
    pr: int
    merged: bool
    sha: str | None = None
    reason: str | None = None


class IntakeRecorded(_Event):
    """AD-27: an issue run's authorisation snapshot."""

    type: Literal["intake_recorded"] = "intake_recorded"
    intake: IssueIntake


class IntakeRejected(_Event):
    """AD-27: the issue has no valid story block, or changed after intake; the story (or the
    run, when there is no story) escalates, and no new step starts."""

    type: Literal["intake_rejected"] = "intake_rejected"
    reason: str


class BudgetExhausted(_Event):
    type: Literal["budget_exhausted"] = "budget_exhausted"
    reason: str


class PauseRequested(_Event):
    """Issue #72: the owner asked the run to pause; no new step starts, running ones finish."""

    type: Literal["pause_requested"] = "pause_requested"


class RunPaused(_Event):
    """Issue #72: the run is `paused` after an owner pause or stop; `sdlc resume` continues."""

    type: Literal["run_paused"] = "run_paused"
    kind: Literal["pause", "stop"]


class RunStopped(_Event):
    """The loop stopped on a fatal error, or on an owner stop (reason `owner stop`, then
    `RunPaused`); audit only, the state stays as it was."""

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
    | UsageWarning
    | PathsReverted
    | ProposalCaptured
    | ProposalDelivered
    | ProposalFailed
    | OwnerReviewAwaited
    | AwaitedSettled
    | IntakeRecorded
    | IntakeRejected
    | BudgetExhausted
    | PauseRequested
    | RunPaused
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


class DeliverProposal(Frozen):
    """AD-26: open the proposal's PR or issue, between ticks, like an escalation."""

    type: Literal["deliver_proposal"] = "deliver_proposal"
    sha256: str


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
    | DeliverProposal
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
