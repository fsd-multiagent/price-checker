"""The loop: load -> decide -> act -> persist (spine "Design Paradigm").

The app is the only place that awaits ports. It runs agent steps as tasks on one asyncio loop
(AD-3), and every result comes back as events applied through `core.apply` and saved by the
store before the next decision (AD-4).
"""

from __future__ import annotations

import asyncio
import contextlib
import dataclasses
import fnmatch
import glob
import json
import shlex
import signal
from collections.abc import Callable
from pathlib import Path

from sdlc.adapters.claude import build_argv, flag_usage_limit
from sdlc.adapters.commands import (
    AGENTS_MD,
    BASE_REF,
    apply_commands_block,
    commands_block,
    restore_commands_block,
)
from sdlc.adapters.git import commit_message
from sdlc.core import ci_log
from sdlc.core import gates as core_gates
from sdlc.core.epics_parser import ParsedStories, parse_epics
from sdlc.core.issues import (
    Authorisation,
    IssueInfo,
    authorise,
    merged_keys,
    parse_issue_story,
    read_snapshot,
    recheck,
    snapshot,
    untrusted_section,
)
from sdlc.core.models import (
    Action,
    AwaitedSettled,
    BlockUnit,
    BudgetExhausted,
    CoreError,
    DeliverProposal,
    EscalationOpened,
    EvaluateGate,
    Event,
    ExhaustBudget,
    Finish,
    GateEvaluated,
    IntakeRecorded,
    IntakeRejected,
    LearnQueue,
    Merged,
    OpenEscalation,
    Outcome,
    OwnerReviewAwaited,
    PathsReverted,
    PauseRequested,
    Pipeline,
    PrOpened,
    Proposal,
    ProposalCaptured,
    ProposalDelivered,
    ProposalFailed,
    ProposalStatus,
    ResetScope,
    RunFinished,
    RunKind,
    RunPaused,
    RunResumed,
    RunStarted,
    RunState,
    RunStopped,
    ScopeReset,
    Severity,
    Stage,
    StartStep,
    StartUnit,
    Status,
    Step,
    StepFinished,
    StepInterrupted,
    StepKind,
    StepResult,
    StepStarted,
    StoriesLoaded,
    Story,
    Tagged,
    TagStage,
    TickInputs,
    UnblockUnit,
    UnitBlocked,
    UnitStarted,
    UnitUnblocked,
    UsageLimited,
    UsageWarning,
    Verdict,
    VerdictSource,
    Wait,
    WorktreeReady,
)
from sdlc.core.outcome import derive_outcome
from sdlc.core.prompts import bullet_list, render_prompt
from sdlc.core.proposals import (
    OWNER_REVIEW_LABEL,
    change_key,
    factory_rules,
    file_diff,
    issue_text,
    owner_review_lines,
    proposal_text,
    text_id,
    tier_paths,
)
from sdlc.core.register_parser import parse_register
from sdlc.core.scheduler import decide, round_records
from sdlc.core.sprint_status import merge_sprint_status
from sdlc.core.state import apply, initial_state, usage_warning_due
from sdlc.core.usage import UNKNOWN_ROLE, agent_slug, render_summary, status_line
from sdlc.ports import Invocation, Ports, ProposalRef, StepKey
from sdlc.store import RunStore, list_runs

SPRINT_STATUS = "_bmad-output/implementation-artifacts/sprint-status.yaml"
USAGE_SUMMARY = ".sdlc/usage-summary.md"  # AD-24: written before an epic's retrospective
DEFAULT_EPICS = "_bmad-output/planning-artifacts/epics.md"
MAX_TICKS = 100_000
STORIES_YAML = "stories.yaml"  # AD-22, AD-27: the issue run's story and its authorisation
ISSUE_JSON = "issue.json"  # AD-27: the issue's title and body, as read at intake
ISSUE_KINDS = (RunKind.STORY, RunKind.FIX)

StoriesReader = Callable[[str], str | None]

PAUSE_FILE = "pause"  # issue #72: beside the lock, `{"run_id": ..., "at": ...}`
OWNER_STOP = "owner stop"


def read_pause_request(path: Path) -> dict[str, str] | None:
    """The pause request in `path`, or None when there is none or it does not parse."""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(data, dict) or not isinstance(data.get("run_id"), str):
        return None
    return data


def write_pause_request(path: Path, run_id: str, at: str) -> None:
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps({"run_id": run_id, "at": at}) + "\n", encoding="utf-8")
    tmp.replace(path)


def remove_pause_request(path: Path, run_id: str) -> bool:
    """Remove the request when it names `run_id`; a request for another run stays."""
    req = read_pause_request(path)
    if req is None or req["run_id"] != run_id:
        return False
    path.unlink(missing_ok=True)
    return True


class LoopError(RuntimeError):
    pass


class IssueRefused(LoopError):
    """AD-27: the issue may not start a run; the reason is commented on the issue."""


def make_run_id(now: str, kind: RunKind) -> str:
    """`YYYYMMDD-HHMMSS-<kind>` from the clock's ISO timestamp (AD-12)."""
    digits = now.replace("-", "").replace(":", "")
    return f"{digits[:8]}-{digits[9:15]}-{kind.value}"


def stories_for_from(
    pipeline: Pipeline, kind: RunKind, from_stage: str, read: StoriesReader
) -> tuple[Story, ...] | None:
    """The stories a `--from` run needs, read before anything is created.

    Returns None when a stage that still runs produces the stories. Raises LoopError when the
    stories file is missing or invalid, or when per-story stages would run with no stories.
    """
    start = pipeline.index(from_stage)
    in_run = [(i, s) for i, s in enumerate(pipeline.stages) if kind in s.entry]
    before = [s for i, s in in_run if s.stories_file and i < start]
    if before:
        rel = before[-1].stories_file or ""
        text = read(rel)
        if text is None:
            raise LoopError(f"--from {from_stage}: {rel} not found")
        parsed = parse_epics(text)
        if parsed.errors:
            raise LoopError(f"--from {from_stage}: {rel}: " + "; ".join(parsed.errors))
        return parsed.stories
    produced_later = any(s.stories_file for i, s in in_run if i >= start)
    per_story = [s.id for i, s in in_run if s.family != "run" and i >= start]
    if per_story and not produced_later:
        raise LoopError(
            f"--from {from_stage}: per-story stage(s) {', '.join(per_story)} have no stories "
            "source (no stage with stories_file)"
        )
    return None


class Orchestrator:
    def __init__(
        self,
        pipeline: Pipeline,
        ports: Ports,
        runs_dir: str | Path,
        repo_root: str | Path,
        prompts_dir: str | Path,
        echo: Callable[[str], None] = print,
    ) -> None:
        self.p = pipeline
        self.ports = ports
        self.runs_dir = Path(runs_dir)
        self.repo = Path(repo_root)
        self.prompts_dir = Path(prompts_dir)
        self.echo = echo
        self.state: RunState | None = None
        self.store: RunStore | None = None
        self._tasks: dict[asyncio.Task[list[Event]], int] = {}
        self._paused_until: str | None = None
        self.on_run: Callable[[str], None] | None = None  # told the run id (the lock file)
        # Issue #72: the owner's pause request file, read on every tick and while waiting,
        # and the stop flag set by SIGTERM (`sdlc stop`). `poll_s` is how often a wait looks.
        self.pause_file: Path | None = None
        self.poll_s = 1.0
        self._stop_requested = False

    def request_stop(self) -> None:
        """Issue #72: `sdlc stop`: interrupt running steps and pause the run."""
        self._stop_requested = True

    # ------------------------------------------------------------------ entry points

    async def start(
        self,
        kind: RunKind = RunKind.FULL,
        *,
        dry_run: bool,
        from_stage: str | None = None,
        fixtures: str | None = None,
        scenario: str | None = None,
        pipeline_path: str | None = None,
        pipeline_sha256: str | None = None,
        stories_reader: StoriesReader | None = None,
        issue: int | None = None,
    ) -> int:
        stories = None
        intake = None
        if kind in ISSUE_KINDS:
            if issue is None or from_stage is not None:
                raise LoopError(f"a {kind.value} run takes an issue number and no --from")
            intake = await self._intake(issue)  # raises IssueRefused before any run dir
        if from_stage is not None:  # validate before the run dir exists
            reader = stories_reader or (lambda rel: self.ports.fs.read_text(str(self.repo / rel)))
            stories = stories_for_from(self.p, kind, from_stage, reader)
        now = self.ports.clock.now()
        run_id = self._free_run_id(make_run_id(now, kind))
        self.store = RunStore(self.runs_dir / run_id, warn=self._warn)
        self.store.create()
        self.state = RunState.model_validate(
            {
                **initial_state(
                    self.p,
                    run_id=run_id,
                    kind=kind,
                    at=now,
                    dry_run=dry_run,
                    from_stage=from_stage,
                    fixtures=fixtures,
                ).model_dump(),
                "scenario": scenario,
                "pipeline_path": pipeline_path,
                "pipeline_sha256": pipeline_sha256,
            }
        )
        self.store.save(self.state)
        self.echo(
            f"run {run_id} ({kind.value}{', dry-run' if dry_run else ''}) in {self.store.dir}"
        )
        if self.on_run is not None:
            self.on_run(run_id)
        self._apply(RunStarted(at=self.ports.clock.now()))
        if stories is not None:
            self._apply(StoriesLoaded(at=self.ports.clock.now(), stories=stories))
        if intake is not None:
            self._record_intake(kind, *intake)
        return await self._loop()

    # ------------------------------------------------------------------ issue runs (AD-27)

    async def _intake(self, number: int) -> tuple[IssueInfo, Authorisation, ParsedStories]:
        """Authorise the issue, then parse its story block. A refusal is commented on the
        issue and raises IssueRefused: no run starts. A bad block starts a run that escalates
        at once (its errors are commented through the escalation)."""
        gh, git = self.ports.gh, self.ports.git
        busy = self._runs_of_issue(number)
        if busy:  # it would reuse the same branch and PR
            raise IssueRefused(f"issue #{number} already has run(s) {', '.join(busy)} in progress")
        info = await gh.issue(number)
        events = await gh.label_events(number)
        auth = authorise(info, events, self.p.settings.owners)
        if not auth.ok:
            why = f"Zamolxe (Orchestrator) will not start a run from this issue: {auth.refusal}."
            try:
                await gh.comment_issue(number, why + " Only an owner can authorise one (AD-27).")
            except Exception as exc:  # the refusal stands without its comment
                self.echo(f"!! could not comment on issue #{number}: {exc!r}")
            raise IssueRefused(f"issue #{number} refused: {auth.refusal}")
        await self._fetch()  # depends_on is checked against origin/main
        epics = await git.show(BASE_REF, self._epics_path())
        main_keys = frozenset(s.key for s in parse_epics(epics or "").stories)
        done = merged_keys(await git.show(BASE_REF, SPRINT_STATUS))
        return info, auth, parse_issue_story(info, main_keys, done)

    def _runs_of_issue(self, number: int) -> list[str]:
        """Runs from the same issue that are not finished (passed or escalated)."""
        out = []
        for run_id in list_runs(self.runs_dir):
            try:
                other = RunState.model_validate_json(
                    (self.runs_dir / run_id / "state.json").read_text(encoding="utf-8")
                )
            except (OSError, ValueError):
                continue
            if (
                other.issue is not None
                and other.issue.issue == number
                and other.status not in (Status.PASSED, Status.ESCALATED)
            ):
                out.append(run_id)
        return out

    async def _fetch(self) -> None:
        """AD-27: a fetch that still fails after the adapter's retries is reported, and the
        run goes on with the origin/main it has; only script steps fail on it."""
        try:
            await self.ports.git.fetch()
        except Exception as exc:
            self.echo(f"!! git fetch failed; going on with the last fetched origin/main: {exc}")

    def _record_intake(
        self, kind: RunKind, info: IssueInfo, auth: Authorisation, parsed: ParsedStories
    ) -> None:
        assert self.store is not None and auth.intake is not None
        now = self.ports.clock.now
        self._apply(IntakeRecorded(at=now(), intake=auth.intake))
        self.store.write_artifact(ISSUE_JSON, json.dumps({"title": info.title, "body": info.body}))
        self.store.write_artifact(STORIES_YAML, snapshot(auth.intake, kind.value, parsed.stories))
        what = "factory story" if auth.intake.factory else "story"
        if parsed.errors:
            self.echo(f"!! issue #{info.number} has no valid story block: {parsed.errors}")
            self._apply(IntakeRejected(at=now(), reason="; ".join(parsed.errors)))
            return
        self.echo(f"   issue #{info.number}: {what} {parsed.stories[0].key}")
        self._apply(StoriesLoaded(at=now(), stories=parsed.stories))

    async def _recheck_issue(self) -> str | None:
        """AD-27 on `sdlc resume`: the issue must still be authorised and unchanged."""
        assert self.state is not None and self.store is not None
        stored = read_snapshot(self.store.read_artifact(STORIES_YAML)) or self.state.issue
        if stored is None:
            return None
        info = await self.ports.gh.issue(stored.issue)
        events = await self.ports.gh.label_events(stored.issue)
        if info.state != "OPEN" and await self._story_merged():
            info = dataclasses.replace(info, state="OPEN")  # its PR closed it (`Closes #n`)
        return recheck(stored, authorise(info, events, self.p.settings.owners))

    async def _story_merged(self) -> bool:
        """The run's story merged, or one of its awaited PRs did."""
        assert self.state is not None
        if any(s.merged_sha for s in self.state.scopes if s.id.startswith("story:")):
            return True
        for a in self.state.awaiting:
            if (await self.ports.gh.pr_state(a.pr))[0] == "MERGED":
                return True
        return False

    async def _settle_awaited(self, *, resume: bool) -> bool:
        """AD-27: look up each awaited PR. Closed: the unit escalates now. Merged: on a resume
        the unit passes and the story is `Merged`; otherwise it only reports True, so the
        caller queues `sdlc resume`."""
        assert self.state is not None
        merged = False
        now = self.ports.clock.now
        for a in self.state.awaiting:
            state, sha, head = await self.ports.gh.pr_state(a.pr)
            reason = None
            if state == "MERGED" and sha and a.head is not None and head == a.head:
                merged = True
                if resume:
                    self.echo(f"   owner merged PR #{a.pr} for {a.scope}")
                    self._apply(
                        AwaitedSettled(
                            at=now(), scope=a.scope, stage=a.stage, pr=a.pr, merged=True, sha=sha
                        )
                    )
                    self._apply(Merged(at=now(), scope=a.scope, sha=sha))
                    await self._comment(f"`{a.scope}` merged (PR #{a.pr}, by the owner).")
                continue
            if state == "MERGED":  # not the commit that was reviewed: a human must look
                reason = (
                    f"PR #{a.pr} merged with head {(head or '?')[:12]}, not the head "
                    f"{(a.head or '?')[:12]} the orchestrator opened it with"
                )
            elif state == "CLOSED":
                reason = f"PR #{a.pr} closed by the owner without merging"
            if reason is None:
                continue
            self.echo(f"!! {reason}: {a.scope} escalated")
            self._apply(
                AwaitedSettled(
                    at=now(), scope=a.scope, stage=a.stage, pr=a.pr, merged=False, reason=reason
                )
            )
            if not resume:  # a resume opens it in its first tick
                await self._escalate(OpenEscalation(scope=a.scope, reason=reason))
        return merged

    async def check_awaited(self, run_id: str) -> bool:
        """AD-27, for any later command: settle the run's closed PRs; True when one is merged
        (the caller then queues `sdlc resume <run_id>`)."""
        self.store = RunStore(self.runs_dir / run_id, warn=self._warn)
        self.state = self.store.load(self.p)
        if not self.state.awaiting:
            return False
        return await self._settle_awaited(resume=False)

    async def _comment(self, text: str) -> None:
        """Closing the loop (AD-27): the run id and the outcome, on the source issue."""
        assert self.state is not None
        if self.state.issue is None:
            return
        body = f"Zamolxe (Orchestrator), run `{self.state.run_id}`: {text}"
        try:
            await self.ports.gh.comment_issue(self.state.issue.issue, body)
        except Exception as exc:  # a missed comment never stops the run
            self.echo(f"!! could not comment on issue #{self.state.issue.issue}: {exc!r}")

    def _factory_scope(self, scope: str) -> bool:
        """AD-27: a story scope of a factory story run."""
        assert self.state is not None
        return self.state.factory and scope.startswith("story:")

    def _warn(self, message: str) -> None:
        self.echo(f"!! {message}")

    def _free_run_id(self, run_id: str) -> str:
        """Two runs started in the same second get a short suffix instead of colliding."""
        candidate, n = run_id, 1
        while (self.runs_dir / candidate).exists():
            n += 1
            candidate = f"{run_id}-{n}"
        return candidate

    async def resume(self, run_id: str) -> int:
        self.store = RunStore(self.runs_dir / run_id, warn=self._warn)
        # Backfills pre-AD-24 usage records, and appends what the ledger lacks (AD-24).
        self.state = self.store.load(self.p, reconcile=True)
        self.echo(f"resume {run_id}")
        if self.pause_file is not None and remove_pause_request(self.pause_file, run_id):
            self.echo("   removed a leftover pause request")  # issue #72
        if self.on_run is not None:
            self.on_run(run_id)
        for scope in self.state.scopes:  # AD-27: a crash may have left git lock files
            if scope.worktree:
                for lock in await self.ports.git.clear_stale_locks(scope.worktree):
                    self.echo(f"   removed the stale git lock {lock}")
        changed = await self._recheck_issue()  # AD-27: before anything changes
        await self._fetch()  # the register and the merge checks read origin/main
        # AD-6: abort any rebase first, then reset interrupted steps to the scope head.
        for scope in self.state.scopes:
            if scope.worktree:
                await self.ports.git.abort_rebase(scope.worktree)
        interrupted = [r for r in self.state.steps if r.status is Status.RUNNING]
        for scope_id in dict.fromkeys(r.scope for r in interrupted):
            scope = self.state.scope(scope_id)
            if scope and scope.worktree and scope.head_sha:
                last = max(r.seq for r in interrupted if r.scope == scope_id)
                await self._discard(scope_id, last)
        for r in interrupted:
            self.echo(f"  reset interrupted step {r.seq} {r.scope}/{r.step} r{r.round}")
            self._apply(StepInterrupted(at=self.ports.clock.now(), seq=r.seq))
        # Settings and limits come from the pipeline as it is now (plan §4.4: fix, then resume).
        self._apply(
            RunResumed(at=self.ports.clock.now(), budget_limit=self.p.settings.run_budget_usd)
        )
        if changed is not None:
            self.echo(f"!! {changed}")
            self._apply(IntakeRejected(at=self.ports.clock.now(), reason=changed))
        await self._settle_awaited(resume=True)
        return await self._loop()

    # ------------------------------------------------------------------ the loop

    def _apply(self, event: Event) -> None:
        assert self.state is not None and self.store is not None
        self.state = apply(self.p, self.state, event)
        self.store.append_event(event)
        self.store.save(self.state)
        if not isinstance(event, UsageWarning) and usage_warning_due(self.p, self.state):
            self._warn_usage()

    def _warn_usage(self) -> None:
        """AD-24: one event, one status line and one notification per budget value."""
        assert self.state is not None
        b = self.state.budget
        self._apply(
            UsageWarning(
                at=self.ports.clock.now(), budget_limit=b.limit, spent=b.spent, reserved=b.reserved
            )
        )
        ratio = self.p.settings.usage_warn_ratio
        line = (
            f"spent ${b.spent:.2f} + reserved ${b.reserved:.2f} reached {ratio:.0%} of the "
            f"run budget ${b.limit:.2f}"
        )
        self.echo(f"!! usage: {line}")
        self.ports.notifier.notify("sdlc usage warning", f"run {self.state.run_id}: {line}")

    async def _inputs(self) -> TickInputs:
        assert self.state is not None
        now = self.ports.clock.now()
        if not any(s.sources for s in self.state.stories):
            return TickInputs(now=now)
        text = await self.ports.git.show("origin/main", self.p.settings.register_path)
        return TickInputs(approved_domains=parse_register(text).approved, now=now)

    async def _loop(self) -> int:
        loop = asyncio.get_running_loop()
        try:
            loop.add_signal_handler(signal.SIGTERM, self.request_stop)
            handler = True
        except (NotImplementedError, RuntimeError, ValueError):  # not the main thread
            handler = False
        try:
            return await self._ticks()
        except BaseException as exc:
            await self._stop(exc)
            raise
        finally:
            if handler:
                loop.remove_signal_handler(signal.SIGTERM)

    # ------------------------------------------------------------------ pause and stop (#72)

    def _control(self) -> str | None:
        """`stop` after SIGTERM, `pause` when the request names this run, else None. A
        request for another run is ignored."""
        assert self.state is not None
        if self._stop_requested:
            return "stop"
        if self.pause_file is not None:
            req = read_pause_request(self.pause_file)
            if req is not None and req["run_id"] == self.state.run_id:
                return "pause"
        return None

    async def _wait_tasks(self, *, pausing: bool = False) -> set[asyncio.Task[list[Event]]]:
        """The tasks that finished; empty when a pause or stop came first (while pausing,
        only a stop)."""
        while True:
            done, _ = await asyncio.wait(
                self._tasks, timeout=self.poll_s, return_when=asyncio.FIRST_COMPLETED
            )
            if done or self._stop_requested or (not pausing and self._control() is not None):
                return done

    def _record(self, done: set[asyncio.Task[list[Event]]]) -> BaseException | None:
        """Apply the finished tasks' events in seq order; the first failure is returned."""
        failure: BaseException | None = None
        for task in sorted(done, key=lambda t: self._tasks[t]):
            self._tasks.pop(task)
            try:
                events = task.result()
            except BaseException as exc:  # a simulated crash, or a bug in a step
                failure = failure or exc
                continue
            for event in events:
                self._apply(event)
                if isinstance(event, StepFinished):
                    self._after_step(event)
        return failure

    async def _cancel(self, tasks: list[asyncio.Task[list[Event]]]) -> list[int]:
        """Cancel `tasks` and record each step as interrupted (its cap is charged, AD-11). A
        task that already finished is recorded as it ended, not cancelled."""
        failure = self._record({t for t in tasks if t.done()})
        if failure is not None:
            raise failure
        tasks = [t for t in tasks if t in self._tasks]
        seqs = [self._tasks.pop(t) for t in tasks]
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        for seq in sorted(seqs):
            r = self.state.step_record(seq) if self.state else None
            if r is not None and r.status is Status.RUNNING:
                self.echo(f"  interrupted step {seq} {r.scope}/{r.step} r{r.round}")
                self._apply(StepInterrupted(at=self.ports.clock.now(), seq=seq))
        return seqs

    async def _pause(self, kind: str) -> int:
        """Issue #72: pause lets agent steps finish (script waits are cancelled; a script
        costs nothing); stop cancels every running step. Interrupted steps' scopes are reset
        to their head once nothing runs (AD-6). The run ends `paused` with exit 0."""
        assert self.state is not None
        now = self.ports.clock.now
        interrupted: list[int] = []
        if kind == "pause":
            self._apply(PauseRequested(at=now()))
            scripts = [
                t for t, seq in self._tasks.items() if self._step_kind_of(seq) is StepKind.SCRIPT
            ]
            interrupted += await self._cancel(scripts)
            self.echo(f".. pausing ({len(self._tasks)} steps finishing)")
            failure: BaseException | None = None
            while self._tasks and not self._stop_requested:
                done = await self._wait_tasks(pausing=True)
                failure = self._record(done) or failure
            if failure is not None:
                raise failure
            if self._stop_requested:
                kind = "stop"
        if kind == "stop":
            interrupted += await self._cancel(list(self._tasks))
        for scope_id in dict.fromkeys(self.state.step_record(s).scope for s in interrupted):
            await self._discard(
                scope_id, max(s for s in interrupted if self.state.step_record(s).scope == scope_id)
            )
        if kind == "stop":
            self._apply(RunStopped(at=now(), reason=OWNER_STOP))
        self._apply(RunPaused(at=now(), kind=kind))  # type: ignore[arg-type]
        if self.pause_file is not None:
            remove_pause_request(self.pause_file, self.state.run_id)
        self.echo(f"run {self.state.run_id} paused at {self.state.paused_at} ({kind}); "
                  f"continue with: sdlc resume {self.state.run_id}")  # fmt: skip
        self.ports.notifier.notify("sdlc paused", f"run {self.state.run_id}: owner {kind}")
        return 0

    def _step_kind_of(self, seq: int) -> StepKind | None:
        assert self.state is not None
        r = self.state.step_record(seq)
        try:
            return self.p.stage(r.stage).step(r.step).kind
        except KeyError:
            return None

    async def _stop(self, exc: BaseException) -> None:
        """A fatal error: cancel and await the steps still running, then log the stop."""
        tasks = list(self._tasks)
        self._tasks.clear()
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        if self.state is not None and self.store is not None:
            try:
                self._apply(RunStopped(at=self.ports.clock.now(), reason=repr(exc)[:500]))
            except Exception as inner:  # the stop record must not hide the original error
                self.echo(f"!! could not record the stop: {inner!r}")

    async def _ticks(self) -> int:
        assert self.state is not None
        for _ in range(MAX_TICKS):
            control = self._control()
            if control is not None:
                return await self._pause(control)
            actions = decide(self.p, self.state, await self._inputs())
            finish = next((a for a in actions if isinstance(a, Finish)), None)
            if finish is not None:
                self._apply(RunFinished(at=self.ports.clock.now(), exit_code=finish.exit_code))
                self.echo(
                    f"run {self.state.run_id} finished: {self.state.status} "
                    f"(exit {finish.exit_code})"
                )
                return finish.exit_code
            for action in actions:
                if self._control() is not None:  # issue #72: no new step after a request
                    break
                await self._do(action)
            if self._control() is not None:
                continue
            if all(isinstance(a, StartStep) for a in actions):
                if not self._tasks:
                    raise LoopError("no progress: nothing to do and nothing running")
                failure = self._record(await self._wait_tasks())
                if failure is not None:  # the other finished steps are recorded first
                    raise failure
        raise LoopError(f"loop exceeded {MAX_TICKS} ticks")

    async def _do(self, action: Action) -> None:
        assert self.state is not None
        now = self.ports.clock.now
        match action:
            case StartUnit():
                scope = self.state.scope(action.scope)
                if scope is None or scope.worktree is None:
                    await self._fetch()  # AD-20: cut from a fresh origin/main
                    path, head = await self.ports.git.ensure_worktree(action.branch, "origin/main")
                    self._apply(
                        WorktreeReady(
                            at=now(),
                            scope=action.scope,
                            branch=action.branch,
                            worktree=path,
                            head_sha=head,
                        )
                    )
                self._apply(UnitStarted(at=now(), scope=action.scope, stage=action.stage))
                unit = self.state.unit(action.scope, action.stage)
                self.echo(f"== {action.scope} :: {action.stage} r{unit.round if unit else '?'}")
            case StartStep():
                self._start_step(action)
            case EvaluateGate():
                self._evaluate_gate(action)
            case ResetScope():
                last = max((r.seq for r in self.state.steps if r.scope == action.scope), default=0)
                patch = await self._discard(action.scope, last)
                self._apply(ScopeReset(at=now(), scope=action.scope, patch=patch))
            case BlockUnit():
                self.echo(f"   blocked {action.scope}/{action.stage}: {action.reason}")
                self._apply(
                    UnitBlocked(
                        at=now(), scope=action.scope, stage=action.stage, reason=action.reason
                    )
                )
            case UnblockUnit():
                self.echo(f"   unblocked {action.scope}/{action.stage}")
                self._apply(UnitUnblocked(at=now(), scope=action.scope, stage=action.stage))
            case OpenEscalation():
                await self._escalate(action)
            case DeliverProposal():
                await self._deliver(action)
            case TagStage():
                scope = self.state.scope(action.scope)
                if scope is not None and scope.head_sha:
                    await self.ports.git.tag(action.tag, scope.head_sha)
                self._apply(
                    Tagged(at=now(), scope=action.scope, stage=action.stage, tag=action.tag)
                )
            case ExhaustBudget():
                self.echo(f"!! budget: {action.reason}")
                self._apply(BudgetExhausted(at=now(), reason=action.reason))
            case Wait():
                # AD-18: a plan usage limit pauses the run; the dry-run clock returns at once.
                self.echo(f".. plan usage limit: paused until {action.until}")
                if action.until != self._paused_until:  # notify once per pause
                    self._paused_until = action.until
                    self.ports.notifier.notify(
                        "sdlc paused", f"plan usage limit: until {action.until}"
                    )
                sleeper = asyncio.ensure_future(self.ports.clock.sleep_until(action.until))
                while not sleeper.done():  # issue #72: a pause or stop ends the wait
                    await asyncio.wait({sleeper}, timeout=self.poll_s)
                    if not sleeper.done() and self._control() is not None:
                        sleeper.cancel()
                        await asyncio.gather(sleeper, return_exceptions=True)
                        return
                sleeper.result()
                self.echo(f".. resumed at {now()}")
            case _:
                raise LoopError(f"unknown action {action!r}")

    async def _discard(self, scope_id: str, seq: int) -> str | None:
        """AD-6: save the discarded diff, then reset the worktree to the scope head."""
        assert self.state is not None and self.store is not None
        scope = self.state.scope(scope_id)
        if scope is None or scope.worktree is None or scope.head_sha is None:
            return None
        diff = await self._fix_diff(scope.worktree, scope.head_sha, self._factory_scope(scope_id))
        await self.ports.git.reset_hard(scope.worktree, scope.head_sha)
        if not diff.strip():
            return None
        return self.store.write_artifact(f"steps/{seq}.discarded.patch", diff)

    async def _fix_diff(self, worktree: str, head_sha: str, factory: bool = False) -> str:
        """AD-26: the discarded diff for the fix prompt leaves out all three tiers and the
        `sdlc-commands` block, so a retry never replays a proposal. The block goes back to the
        scope head's first; the worktree is reset right after. A factory story's diff keeps
        protected and review paths and the block: they are its work (AD-27)."""
        s = self.p.settings
        if factory:
            return await self.ports.git.diff(worktree, head_sha, exclude=s.owner_only_paths)
        at_head = await self.ports.git.show(head_sha, AGENTS_MD)
        if at_head is not None:
            path = f"{worktree}/{AGENTS_MD}"
            restored = restore_commands_block(self.ports.fs.read_text(path), at_head)
            if restored is not None:
                self.ports.fs.write_text(path, restored[0])
        return await self.ports.git.diff(
            worktree,
            head_sha,
            exclude=(*s.owner_only_paths, *s.protected_paths, *s.review_paths),
        )

    # ------------------------------------------------------------------ steps

    def _start_step(self, action: StartStep) -> None:
        assert self.state is not None
        stage = self.p.stage(action.stage)
        step = stage.step(action.step)
        scope = self.state.scope(action.scope)
        assert scope is not None and scope.worktree is not None
        seq = self.state.next_seq
        key = StepKey(
            seq=seq, scope=action.scope, stage=action.stage, step=action.step, round=action.round
        )
        self._apply(
            StepStarted(
                at=self.ports.clock.now(),
                seq=seq,
                scope=action.scope,
                stage=action.stage,
                step=action.step,
                round=action.round,
                cap=action.cap,
            )
        )
        if step.kind is StepKind.SCRIPT:
            self.echo(
                f"  [{seq:>3}] {action.scope}/{step.id} r{action.round}  script {step.script}"
            )
            coro = self._run_script(key, stage, step, scope.worktree)
        else:
            try:
                if stage.learn_queue is LearnQueue.EPIC:
                    self._write_usage_summary(key, scope.worktree)
                inv = self._invocation(key, stage, step, scope.worktree, action.cap)
            except Exception as exc:  # a broken prompt fails the step, not the run
                self.echo(
                    f"  [{seq:>3}] {action.scope}/{step.id} r{action.round}  cannot start: {exc}"
                )
                coro = self._failed(key, f"could not build the invocation: {exc}")
            else:
                self.echo(
                    f"  [{seq:>3}] {action.scope}/{step.id} r{action.round}  "
                    f"(cwd {inv.cwd}) $ {shlex.join(inv.argv)} < {step.prompt}.md"
                )
                coro = self._run_agent(key, step, scope.worktree, inv)
        self._tasks[asyncio.ensure_future(coro)] = seq

    def _write_usage_summary(self, key: StepKey, worktree: str) -> None:
        """AD-24: the epic's usage in this run, for the retrospective to read."""
        assert self.state is not None
        unit = self.state.unit(key.scope, key.stage)
        subject = (unit.subject if unit else None) or ""
        number = subject.rsplit("-", 1)[-1]  # an epic unit's subject is `epic-<n>`
        if not number.isdigit():
            self.echo(f"        no usage summary: '{subject}' names no epic number")
            return
        summary = render_summary(self.state, int(number))
        self.ports.fs.write_text(f"{worktree}/{USAGE_SUMMARY}", summary)

    async def _failed(self, key: StepKey, why: str) -> list[Event]:
        return [
            StepFinished(
                at=self.ports.clock.now(),
                seq=key.seq,
                result=StepResult(subtype="error_orchestrator", is_error=True, errors=(why,)),
            )
        ]

    def _verdict_path(self, stage: Stage, step: Step, worktree: str) -> str:
        gate = stage.gate_of_step(step.id).kind
        return f"{worktree}/.sdlc/verdicts/{gate}/{step.id}.json"

    def _invocation(
        self, key: StepKey, stage: Stage, step: Step, worktree: str, cap: float
    ) -> Invocation:
        assert self.state is not None
        unit = self.state.unit(key.scope, key.stage)
        assert unit is not None
        template = self.ports.fs.read_text(str(self.prompts_dir / f"{step.prompt}.md"))
        if template is None:
            raise LoopError(f"prompt template {step.prompt}.md not found")
        # Only a file verdict has a path; a result_block verdict is the last json block (AD-17).
        verdict_path = (
            self._verdict_path(stage, step, worktree)
            if step.verdict_source is VerdictSource.FILE
            else ""
        )
        story = self.state.story(unit.subject or "")
        issue_text, story_title, story_source = self._issue_context(story)
        context = {
            "run_id": self.state.run_id,
            "scope": key.scope,
            "stage": key.stage,
            "step": key.step,
            "round": str(key.round),
            "worktree": worktree,
            "verdict_path": verdict_path or "(none: this step writes no verdict file)",
            "inputs": bullet_list(step.inputs, "- (none)"),
            "story_key": story.key if story else (unit.subject or "(none)"),
            "story_title": story_title,
            "story_sources": ", ".join(story.sources) if story and story.sources else "(none)",
            "story_touches": ", ".join(story.touches) if story and story.touches else "(none)",
            "epics_path": self._epics_path(),
            "story_source": story_source,
            "feedback": self._feedback(unit.feedback, worktree, key.round),
            "factory_rules": factory_rules(self.p.settings),
            "issue_text": issue_text,
        }
        env = {"TMPDIR": f"{worktree}/.sdlc/tmp", "SDLC_RUN_ID": self.state.run_id}
        if verdict_path:
            env["SDLC_VERDICT_PATH"] = verdict_path
        limits = stage.limits_for(step, self.p.settings)
        argv = build_argv(
            budget_usd=cap,
            allowed_tools=step.tools,
            restrict_tools=not step.writes,
            agent=step.agent,
            model=limits.model,
            effort=limits.effort,
            max_turns=limits.max_turns,
        )
        return Invocation(
            key=key,
            argv=argv,
            stdin=render_prompt(template, context),
            cwd=worktree,
            env=env,
            timeout_s=float((stage.timeout_min or self.p.settings.timeout_min) * 60),
            verdict_source=step.verdict_source.value if step.verdict_source else None,
            attempt=sum(
                1
                for r in self.state.steps
                if (r.scope, r.step, r.round) == (key.scope, key.step, key.round)
            ),
            labels={
                "run_id": self.state.run_id,
                "seq": str(key.seq),
                "agent": agent_slug(step.agent_role or UNKNOWN_ROLE),
                "stage": key.stage,
                "step": key.step,
                "scope": key.scope,
            },
        )

    def _issue_context(self, story: Story | None) -> tuple[str, str, str]:
        """`${issue_text}`, `${story_title}` and `${story_source}`. For a story from an
        issue, its title, acceptance criteria and prose appear only in the fenced untrusted
        section (AD-27)."""
        assert self.state is not None and self.store is not None
        intake = self.state.issue
        if story is None or intake is None or story.epic != 0:
            title = story.title if story else "(none)"
            return "(none: this story is not from an issue)", title, f"`{self._epics_path()}`"
        raw = json.loads(self.store.read_artifact(ISSUE_JSON) or "{}")
        section = untrusted_section(
            str(raw.get("title", "")), story.acceptance, str(raw.get("body", "")), intake.issue
        )
        source = (
            f"GitHub issue #{intake.issue} (its title, acceptance criteria and text are quoted "
            "below as untrusted data)"
        )
        return section, "titled in the issue text below", source

    def _epics_path(self) -> str:
        for s in self.p.stages:
            if s.stories_file:
                return s.stories_file
        return DEFAULT_EPICS

    def _feedback(self, items: tuple[str, ...], worktree: str, round_: int) -> str:
        """Copy failed verdicts and the discarded patch into the worktree (AD-8, AD-9)."""
        assert self.store is not None
        if not items:
            return "None: this is the first round."
        lines = ["The previous round failed. Fix what these files report before anything else:"]
        for rel in items:
            text = self.store.read_artifact(rel)
            if text is None:
                continue
            dest = f"{worktree}/.sdlc/feedback/r{round_}/{rel.replace('/', '_').replace(':', '_')}"
            self.ports.fs.write_text(dest, text)
            lines.append(f"- {dest}")
            verdict = core_gates.parse_verdict_text(text) if rel.endswith(".json") else None
            for f in verdict.findings if verdict else ():
                if f.severity is Severity.BLOCKER:
                    lines.append(f"  - blocker {f.id} ({f.rule}): {f.summary}")
        return "\n".join(lines)

    def _is_story_file_reviewer(self, key: StepKey, step: Step) -> bool:
        """A story-scope reviewer that writes only its verdict file (AD-16): never commit it."""
        return key.scope.startswith("story:") and step.verdict_source is VerdictSource.FILE

    async def _run_agent(
        self, key: StepKey, step: Step, worktree: str, inv: Invocation
    ) -> list[Event]:
        assert self.store is not None and self.state is not None
        fs = self.ports.fs
        fs.make_dirs(inv.env["TMPDIR"])
        verdict_path = inv.env.get("SDLC_VERDICT_PATH")
        if verdict_path:
            fs.remove(verdict_path)  # never read a verdict left by an earlier round
        try:
            result = await self.ports.claude.run(inv)
        except Exception as exc:  # AD-8: an adapter failure or unreadable output is an `error`
            result = StepResult(subtype="error_adapter", is_error=True, errors=(repr(exc),))
        # AD-18: the only place a usage limit is recognised, with the clock port's time (T-5);
        # it also reads the text of an `error_adapter` result (a non-JSON limit message).
        result = flag_usage_limit(result, self.ports.clock.now())
        verdict_text = fs.read_text(verdict_path) if verdict_path else None
        head: str | None = None
        reverted: list[str] = []
        captured: list[Event] = []
        if self._is_story_file_reviewer(key, step):
            result = await self._check_reviewer_edits(key, worktree, result)
        else:
            head, reverted, captured = await self._commit_if_done(
                key, step, worktree, result, verdict_text
            )
        base = f"steps/{key.seq}-{key.scope}-{key.step}"
        # AD-24: the step file keeps the raw result JSON without the `result` text; `raw` is
        # excluded from every other dump (events, state).
        step_file = {**result.model_dump(mode="json", exclude={"result"}), "raw": result.raw}
        self.store.write_artifact(f"{base}.json", json.dumps(step_file, indent=2) + "\n")
        note = (
            f"\n---\nreverted on this branch, not committed (AD-24, AD-26, ADR 0021): "
            f"{', '.join(reverted)}\n"
            if reverted
            else ""
        )
        for c in captured:
            if isinstance(c, ProposalCaptured):
                note += f"proposal for owner review (AD-26): {c.patch} ({', '.join(c.paths)})\n"
        self.store.write_artifact(
            f"{base}.log",
            f"$ {shlex.join(inv.argv)}\n\n{inv.stdin}\n\n---\n{result.result or ''}\n{note}",
        )
        events: list[Event] = []  # a ProposalCaptured is applied when it is captured
        if reverted:
            self.echo(f"        reverted on {key.scope}: {', '.join(reverted)}")
            events.append(
                PathsReverted(
                    at=self.ports.clock.now(), scope=key.scope, seq=key.seq, paths=tuple(reverted)
                )
            )
        for d in result.permission_denials:
            self.echo(f"        permission denied: {d.tool_name} {d.detail}")
        return [
            *events,
            StepFinished(
                at=self.ports.clock.now(),
                seq=key.seq,
                result=result,
                verdict_text=verdict_text,
                head_sha=head,
            ),
        ]

    def _after_step(self, event: StepFinished) -> None:
        """Record the audit `UsageLimited` for a step the core closed as superseded (AD-18)."""
        assert self.state is not None
        rec = self.state.step_record(event.seq)
        if rec.status is Status.SUPERSEDED:
            self.echo(f"        plan usage limit hit by {rec.scope}/{rec.step}: superseded")
            self._apply(
                UsageLimited(
                    at=self.ports.clock.now(), seq=rec.seq, resets_at=event.result.resets_at
                )
            )

    async def _check_reviewer_edits(
        self, key: StepKey, worktree: str, result: StepResult
    ) -> StepResult:
        """A reviewer that changed files outside `.sdlc/` fails, and its edits are reset."""
        assert self.state is not None
        changed = await self.ports.git.changed_paths(worktree)
        outside = [p for p in changed if not p.startswith(".sdlc/")]
        if not outside:
            return result
        scope = self.state.scope(key.scope)
        if scope is not None and scope.head_sha:
            await self.ports.git.reset_hard(worktree, scope.head_sha)
        why = f"reviewer changed files outside .sdlc/: {', '.join(outside[:10])} (AD-16)"
        self.echo(f"        {why}")
        return StepResult.model_validate(
            {
                **result.model_dump(),
                "raw": result.raw,  # excluded from dumps; carried to the step file
                "subtype": "error_reviewer_edited",
                "is_error": True,
                "errors": [*result.errors, why],
            }
        )

    async def _commit_if_done(
        self, key: StepKey, step: Step, worktree: str, result: StepResult, verdict_text: str | None
    ) -> tuple[str | None, list[str], list[Event]]:
        """AD-6, AD-21: leftovers are committed only when a writing step ends `done`. Returns
        the new head, the paths reverted before the commit (AD-24, AD-26), and the proposal
        captured from them (AD-26)."""
        reverted: list[str] = []
        captured: list[Event] = []
        if not step.writes:
            return None, reverted, captured
        if step.verdict_source is VerdictSource.RESULT_BLOCK:
            verdict = core_gates.last_verdict_block(result.result)
        else:
            verdict = core_gates.parse_verdict_text(verdict_text)
        readiness = self.p.stage(key.stage).gate_of_step(step.id).kind == "readiness"
        if derive_outcome(step, result, verdict, readiness=readiness).outcome is not Outcome.DONE:
            return None, reverted, captured
        try:
            reverted, captured = await self._pre_commit_guards(
                key.scope, worktree, key.seq, step.agent_role
            )
            assert self.state is not None
            sha = await self.ports.git.commit_all(
                worktree, commit_message(self.state.run_id, key.scope, key.step, key.round)
            )
            return sha or await self.ports.git.head(worktree), reverted, captured
        except Exception as exc:
            self.echo(f"        commit failed: {exc!r}")
            return None, reverted, captured

    async def _pre_commit_guards(
        self, scope: str, worktree: str, seq: int, role: str | None
    ) -> tuple[list[str], list[Event]]:
        """What every orchestrator commit on an agent branch runs first. Returns the paths it
        put back, for the step log and the `paths_reverted` event, and the proposal captured
        before the revert.

        - Every branch (AD-26): changes under `protected_paths`, and on a learn branch under
          `review_paths` too, are captured as a proposal (with a change to the
          `sdlc-commands` block of `AGENTS.md`), then reverted with the `owner_only_paths`.
        - Story branches revert the bookkeeping paths to the scope head (AD-21), `AGENTS.md`
          included, so the gate block cannot change there.
        - Learn branches revert `learn_revert_paths` (AD-24).
        - Every scope other than a story puts the `sdlc-commands` block back as it is on
          `origin/main` (ADR 0021): planning, infra and learn PRs auto-merge.
        """
        git, settings = self.ports.git, self.p.settings
        changed = await git.changed_paths(worktree)
        owner = tier_paths(changed, settings.owner_only_paths)
        if self._factory_scope(scope):
            return await self._factory_guards(worktree, owner), []
        tiers = settings.protected_paths
        if scope.startswith("learn:"):  # a learn PR auto-merges and never carries CI changes
            tiers = (*tiers, *settings.review_paths)
        protected = [p for p in tier_paths(changed, tiers) if p not in owner]
        captured = await self._capture(scope, worktree, seq, role, protected)
        reverted = [*owner, *protected]
        if reverted:
            await git.restore_paths(worktree, tuple(glob.escape(p) for p in reverted))
        if scope.startswith("story:"):
            await git.restore_paths(worktree, settings.bookkeeping_paths)
            return reverted, captured
        if scope.startswith("learn:"):
            patterns = settings.learn_revert_paths
            reverted += [
                path
                for path in await git.changed_paths(worktree)
                if any(fnmatch.fnmatchcase(path, pat) for pat in patterns) and path not in reverted
            ]
            await git.restore_paths(worktree, patterns)
        what = await self._restore_gate_commands(worktree)
        if what is not None:
            reverted.append(AGENTS_MD)
            self.echo(
                f"        restored {AGENTS_MD} ({what}) from {BASE_REF} on {scope} (ADR 0021)"
            )
        return reverted, captured

    async def _factory_guards(self, worktree: str, owner: list[str]) -> list[str]:
        """AD-27, a factory story: protected and review paths are kept; owner-only paths and
        bookkeeping are reverted; in `AGENTS.md` only the `sdlc-commands` block change is kept,
        spliced into the scope head's file."""
        git, fs = self.ports.git, self.ports.fs
        reverted = list(owner)
        if owner:
            await git.restore_paths(worktree, tuple(glob.escape(p) for p in owner))
        path = f"{worktree}/{AGENTS_MD}"
        agent = fs.read_text(path)
        await git.restore_paths(worktree, self.p.settings.bookkeeping_paths)
        head = fs.read_text(path)
        kept = apply_commands_block(head, agent) if head is not None else None
        if kept is not None:
            fs.write_text(path, kept)
        if agent is not None and agent != (kept if kept is not None else head):
            reverted.append(AGENTS_MD)  # its lines outside the block went back
        return reverted

    async def _capture(
        self, scope: str, worktree: str, seq: int, role: str | None, protected: list[str]
    ) -> list[Event]:
        """AD-26: the protected changes as one patch, saved and recorded before any revert.

        For `AGENTS.md` only the `sdlc-commands` block is proposed, and only when the agent
        changed it compared with the scope's branch point (ADR 0023): origin/main's file with
        the agent's block spliced in. A block the agent deleted or added proposes the whole
        file. The key is the normalised change, so the same edit from two scopes is one
        proposal."""
        assert self.store is not None
        git = self.ports.git
        parts: list[str] = []
        entries: list[tuple[str, str | None]] = []
        paths = list(protected)
        if protected:
            staged = await git.staged_diff(worktree, tuple(protected))
            parts.append(staged.patch)
            entries += staged.entries
        agent = self.ports.fs.read_text(f"{worktree}/{AGENTS_MD}")
        point = await git.branch_point(worktree)
        at_point = await git.show(point or BASE_REF, AGENTS_MD)
        mine, theirs = commands_block(agent), commands_block(at_point)
        if mine != theirs:
            base = await git.show(BASE_REF, AGENTS_MD)
            new: str | None = agent  # a deleted or added block: the whole file
            if mine is not None and theirs is not None and commands_block(base) is not None:
                new = apply_commands_block(base or "", agent) or base  # base: main has it
            if new != base:
                parts.append(file_diff(AGENTS_MD, base, new))
                entries.append((AGENTS_MD, text_id(new)))
                paths.append(AGENTS_MD)
        patch = "".join(parts)
        if not patch.strip():
            return []
        rel = self.store.write_artifact(f"steps/{seq}.proposal.patch", patch)
        event = ProposalCaptured(
            at=self.ports.clock.now(),
            sha256=change_key(entries),
            scope=scope,
            seq=seq,
            agent_role=role,
            paths=tuple(paths),
            patch=rel,
            review=bool(tier_paths(paths, self.p.settings.review_paths)),
        )
        self._apply(event)  # recorded before anything is reverted
        self.echo(f"        proposal for owner review from {scope}: {', '.join(paths)} (AD-26)")
        return [event]

    async def _restore_gate_commands(self, worktree: str) -> str | None:
        """ADR 0021: put the `sdlc-commands` block back as it is on `origin/main`. Returns
        what was restored (`"sdlc-commands block"` or `"whole file"`), or None."""
        base = await self.ports.git.show(BASE_REF, AGENTS_MD)
        if base is None:
            return None
        path = f"{worktree}/{AGENTS_MD}"
        restored = restore_commands_block(self.ports.fs.read_text(path), base)
        if restored is None:
            return None
        text, what = restored
        self.ports.fs.write_text(path, text)
        return what

    async def _run_script(
        self, key: StepKey, stage: Stage, step: Step, worktree: str
    ) -> list[Event]:
        assert self.state is not None
        state = self.state
        scope = state.scope(key.scope)
        assert scope is not None
        events: list[Event] = []
        head: str | None = None
        timeout = float((stage.timeout_min or self.p.settings.timeout_min) * 60)
        git, gh = self.ports.git, self.ports.gh
        try:
            match step.script:
                case "tests" | "terraform_plan" | "fr_ac_check":
                    code, out = await self.ports.commands.run(
                        step.script, worktree, timeout, key, factory=self._factory_scope(key.scope)
                    )
                case "rebase":
                    await git.fetch()
                    ok = await git.rebase(worktree, "origin/main", key=key)
                    if ok:
                        code, out = 0, "rebased onto origin/main"
                        head = await git.head(worktree)
                    else:
                        await git.abort_rebase(worktree)  # never leave it mid-rebase
                        code, out = 1, "rebase conflict; rebase aborted"
                case "open_pr":
                    code, out, opened, head = await self._open_pr(key, worktree)
                    events += opened
                case "ci_green":
                    ok = scope.pr is not None and await gh.checks_green(
                        scope.pr, self.p.settings.ci_timeout_min, key=key
                    )
                    code, out = (0, "required checks green") if ok else (1, "checks not green")
                    if not ok and scope.pr is not None:
                        out += await self._ci_excerpt(scope.pr, key)
                case "merge_pr":
                    if scope.pr is None or not scope.head_sha:
                        code, out = 1, "no PR or no head SHA to merge"
                    else:
                        sha = await gh.merge(
                            scope.pr,
                            scope.head_sha,
                            key=key,
                            factory=self._factory_scope(key.scope),
                            paths=await git.branch_paths(worktree),  # AD-27: not the PR's list
                        )
                        if sha:
                            events.append(
                                Merged(at=self.ports.clock.now(), scope=key.scope, sha=sha)
                            )
                            if key.scope.startswith("story:"):
                                await self._comment(f"`{key.scope}` merged (PR #{scope.pr}).")
                        code, out = (0, f"merged {sha}") if sha else (1, "not merged")
                case "render_sprint_status":
                    # AD-27: merged into main's file, never substituted for it.
                    main = await git.show(BASE_REF, SPRINT_STATUS)
                    text = merge_sprint_status(self.p, state, main)
                    self.ports.fs.write_text(f"{worktree}/{SPRINT_STATUS}", text)
                    reverted, _ = await self._pre_commit_guards(key.scope, worktree, key.seq, None)
                    head = await git.commit_all(
                        worktree, commit_message(state.run_id, key.scope, key.step, key.round)
                    )
                    code, out = (
                        0,
                        f"rendered {SPRINT_STATUS}"
                        + (f"; reverted {', '.join(reverted)}" if reverted else ""),
                    )
                case _:
                    code, out = 1, f"unknown script {step.script}"
        except Exception as exc:
            code, out = 1, f"{step.script} failed: {exc!r}"
            events = []
        result = StepResult(subtype="success", exit_code=code, result=out)
        assert self.store is not None
        self.store.write_artifact(
            f"steps/{key.seq}-{key.scope}-{key.step}.json", result.model_dump_json(indent=2)
        )
        return [
            *events,
            StepFinished(
                at=self.ports.clock.now(),
                seq=key.seq,
                result=result,
                head_sha=head if code == 0 else None,
            ),
        ]

    async def _ci_excerpt(self, pr: int, key: StepKey) -> str:
        """Issue #87: the failed jobs' log excerpt of the PR head, or "" when it cannot be read
        (a failing fetch never turns red CI green and never raises)."""
        try:
            text = ci_log.excerpt(await self.ports.gh.failed_ci_log(pr, key=key))
        except Exception:
            return ""
        return f"\nfailed CI jobs (excerpt):\n{text}" if text else ""

    async def _open_pr(
        self, key: StepKey, worktree: str
    ) -> tuple[int, str, list[Event], str | None]:
        """Push and open the PR; returns the code, the output, the events and the new head
        when a learn branch was rebased. AD-27:
        - a learn branch rebases onto origin/main first, so a stale one cannot conflict;
        - a story branch that carries owner-only paths, or protected paths outside a factory
          story, fails (a guard bug);
        - a PR touching review paths, protected paths in a factory story, or (any story) the
          `sdlc-commands` block, is created labelled owner-review and waits."""
        assert self.state is not None
        state, git, gh, settings = self.state, self.ports.git, self.ports.gh, self.p.settings
        scope = state.scope(key.scope)
        assert scope is not None
        rebased: str | None = None
        if key.scope.startswith("learn:"):
            await git.fetch()
            if not await git.rebase(worktree, BASE_REF, key=key):
                await git.abort_rebase(worktree)
                return 1, "learn branch: rebase conflict; rebase aborted (AD-27)", [], None
            rebased = await git.head(worktree)
        paths = await git.branch_paths(worktree)
        factory = self._factory_scope(key.scope)
        if key.scope.startswith("story:"):
            bad = tier_paths(paths, settings.owner_only_paths)
            if not factory:
                bad += tier_paths(paths, settings.protected_paths)
            if bad:
                return (
                    1,
                    (
                        f"guard bug: the branch still changes {', '.join(sorted(bad)[:10])} after "
                        "the pre-commit revert (AD-26, AD-27); not pushed"
                    ),
                    [],
                    None,
                )
        review = tier_paths(paths, settings.review_paths)
        if factory:
            review += tier_paths(paths, settings.protected_paths)
        if key.scope.startswith("story:") and await self._block_changed(worktree):
            review.append(f"{AGENTS_MD} (the sdlc-commands block)")
        await git.push(worktree, scope.branch)
        pushed = await git.head(worktree)
        title = f"sdlc({state.run_id}): {key.scope}"
        lines = [f"Opened by Zamolxe (Orchestrator) for {key.scope} in run {state.run_id}."]
        if review:
            lines += ["", f"Waits for owner review (`{OWNER_REVIEW_LABEL}`): it touches "
                      f"{', '.join(sorted(review)[:10])}. Zamolxe (Orchestrator) never merges "
                      "it (AD-27)."]  # fmt: skip
        if state.issue is not None and key.scope.startswith("story:"):
            lines += ["", f"Closes #{state.issue.issue}"]
        labels = (OWNER_REVIEW_LABEL,) if review else ()  # set when the PR is created
        number = await gh.ensure_pr(scope.branch, "main", title, "\n".join(lines), labels)
        now = self.ports.clock.now
        events: list[Event] = [PrOpened(at=now(), scope=key.scope, number=number)]
        if not review:
            return 0, f"PR #{number}", events, rebased
        events.append(
            OwnerReviewAwaited(at=now(), scope=key.scope, stage=key.stage, pr=number, head=pushed)
        )
        self.echo(f"   owner review: PR #{number} for {key.scope} waits for the owner's merge")
        self.ports.notifier.notify(
            "Zamolxe (Orchestrator) owner review",
            f"PR #{number} for {key.scope} waits for your review and merge",
        )
        return 0, f"PR #{number}, awaiting owner review", events, rebased

    async def _block_changed(self, worktree: str) -> bool:
        """AD-27: the `sdlc-commands` block differs from the branch point's (ADR 0023)."""
        point = await self.ports.git.branch_point(worktree)
        at_point = await self.ports.git.show(point or BASE_REF, AGENTS_MD)
        mine = self.ports.fs.read_text(f"{worktree}/{AGENTS_MD}")
        return commands_block(mine) != commands_block(at_point)

    # ------------------------------------------------------------------ gates

    def _evaluate_gate(self, action: EvaluateGate) -> None:
        assert self.state is not None and self.store is not None
        stage = self.p.stage(action.stage)
        unit = self.state.unit(action.scope, action.stage)
        scope = self.state.scope(action.scope)
        assert unit is not None and scope is not None
        records = list(round_records(self.state, unit).values())
        files: dict[str, str | None] = {}
        if stage.stories_file and scope.worktree:
            files[stage.stories_file] = self.ports.fs.read_text(
                f"{scope.worktree}/{stage.stories_file}"
            )
        outputs: dict[int, str] = {}
        for r in records:  # a failed script step's output goes into its finding (issue #87)
            if r.outcome is not Outcome.DONE and stage.step(r.step).kind is StepKind.SCRIPT:
                text = self.store.read_artifact(f"steps/{r.seq}-{r.scope}-{r.step}.json")
                if text:
                    with contextlib.suppress(ValueError):
                        outputs[r.seq] = StepResult.model_validate_json(text).result or ""
        sv = core_gates.evaluate_stage(
            stage, records, files, f"{action.scope} r{action.round}", outputs
        )
        base = f"verdicts/{action.scope}/r{action.round}"
        paths: dict[str, list[str]] = {}
        for kind, verdict in sv.verdicts:
            paths[kind] = [self.store.write_artifact(f"{base}/{kind}.json", _dump(verdict))]
        for r in records:
            if r.verdict is not None:
                kind = stage.gate_of_step(r.step).kind
                paths[kind].append(
                    self.store.write_artifact(f"{base}/{kind}/{r.step}.json", _dump(r.verdict))
                )
        feedback = tuple(paths[sv.failed_gate]) if sv.failed_gate else ()
        verdict_word = f"FAIL ({sv.failed_gate})" if sv.failed_gate else "pass"
        self.echo(f"   gate {action.scope}/{action.stage} r{action.round}: {verdict_word}")
        self._apply(
            GateEvaluated(
                at=self.ports.clock.now(),
                scope=action.scope,
                stage=action.stage,
                round=action.round,
                verdicts=sv.verdicts,
                failed_gate=sv.failed_gate,
                feedback=feedback,
                stories=sv.stories,
            )
        )
        after = self.state.unit(action.scope, action.stage)
        if after is not None and after.status is Status.ESCALATED:
            self.echo(f"!! escalated {action.scope}/{action.stage}: {after.reason}")

    # ------------------------------------------------------------------ escalation

    async def _escalate(self, action: OpenEscalation) -> None:
        """AD-13, AD-14: one issue per (run, scope), found by its marker; then a notification."""
        assert self.state is not None and self.store is not None
        state = self.state
        logs = [
            f"steps/{r.seq}-{r.scope}-{r.step}.log"
            for r in state.steps
            if r.scope == action.scope and r.status is not Status.RUNNING
        ]
        verdicts = [f for u in state.units if u.scope == action.scope for f in u.feedback]
        body = "\n".join(
            [
                f"Zamolxe (Orchestrator) escalated `{action.scope}` in run `{state.run_id}`.",
                "",
                f"Reason: {action.reason}",
                "",
                f"Run dir: `{self.store.dir}`",
                "Verdicts and discarded patches:",
                bullet_list(verdicts, "- (none)"),
                "Step logs:",
                bullet_list(logs[-10:], "- (none)"),
                "",
                f"Fix the input, then run `sdlc resume {state.run_id}`.",
            ]
        )
        title = f"Escalation: {action.scope} in run {state.run_id}"
        self.store.write_artifact(f"escalations/{action.scope}.md", body)
        number = await self.ports.gh.ensure_escalation_issue(
            state.run_id, action.scope, title, body
        )
        self.ports.notifier.notify(
            "Zamolxe (Orchestrator) escalation", f"{action.scope}: {action.reason}"
        )
        self.echo(f"!! escalation issue #{number} for {action.scope}: {action.reason}")
        self._apply(EscalationOpened(at=self.ports.clock.now(), scope=action.scope, issue=number))
        await self._comment(f"`{action.scope}` escalated (issue #{number}): {action.reason}")

    # ------------------------------------------------------------------ proposals

    async def _deliver(self, action: DeliverProposal) -> None:
        """AD-26: one PR or issue per proposal, found by its marker first. A failure is
        recorded and the run goes on; `sdlc resume` tries again."""
        assert self.state is not None
        prop = self.state.proposal(action.sha256)
        if prop is None or prop.status is not ProposalStatus.PENDING:
            return
        now = self.ports.clock.now
        try:
            ref, note = await self._deliver_proposal(prop)
        except Exception as exc:
            self.echo(f"!! proposal {prop.sha256[:8]} from {prop.scope} not delivered: {exc!r}")
            self._apply(ProposalFailed(at=now(), sha256=prop.sha256, reason=repr(exc)[:300]))
            self.ports.notifier.notify(  # once: a failed proposal waits for `sdlc resume`
                "Zamolxe (Orchestrator) owner review",
                f"proposal {prop.sha256[:8]} from {prop.scope} not delivered: {exc}"[:300],
            )
            return
        self._apply(
            ProposalDelivered(
                at=now(), sha256=prop.sha256, kind=ref.kind, number=ref.number, reason=note
            )
        )
        where = f"{'PR' if ref.kind == 'pr' else 'issue'} #{ref.number}"
        self.echo(f"   owner review: {where} for {prop.scope} ({', '.join(prop.paths)})")
        if ref.created:
            self.ports.notifier.notify(
                "Zamolxe (Orchestrator) owner review",
                f"{where}: {prop.agent_role or prop.scope} proposes a change to "
                f"{', '.join(prop.paths[:3])}",
            )

    async def _deliver_proposal(self, prop: Proposal) -> tuple[ProposalRef, str | None]:
        assert self.state is not None and self.store is not None
        git, gh, state = self.ports.git, self.ports.gh, self.state
        found = await gh.find_proposal(prop.sha256)
        if found is not None:  # closed or merged ones too: never reopened
            return found, "found by its marker"
        patch = self.store.read_artifact(prop.patch)
        if patch is None:
            raise LoopError(f"proposal patch {prop.patch} is missing")
        more = f" and {len(prop.paths) - 1} more" if len(prop.paths) > 1 else ""
        title = f"Owner review: change to {prop.paths[0]}{more} ({prop.scope}, run {state.run_id})"
        if prop.review:
            note = "It touches review_paths (workflows), so it is never pushed (AD-26)."
            ref = await gh.ensure_proposal_issue(
                prop.sha256, title, issue_text(state, prop, patch, note), looked_up=True
            )
            return ref, note
        await git.fetch()
        path, _ = await git.ensure_worktree(prop.branch, BASE_REF)
        ended: str | None = None
        try:
            await git.reset_hard(path, BASE_REF)
            if await git.apply_3way(path, patch):
                message = f"sdlc({state.run_id}): proposal {prop.sha256[:8]} from {prop.scope}"
                if await git.commit_all(path, f"{message} step {prop.seq}") is not None:
                    # The branch is the orchestrator's: a retry after a failed `gh pr create`
                    # overwrites what an earlier attempt pushed.
                    await git.push(path, prop.branch, force=True)
                    body = proposal_text(state, prop)
                    ref = await gh.ensure_proposal_pr(
                        prop.sha256, prop.branch, title, body, looked_up=True
                    )
                    ended = "pr"
                    return ref, None
                note = "The patch changes nothing on main, so there is no PR."
            else:
                note = "The patch does not apply on main (git apply --3way), so there is no PR."
            await git.reset_hard(path, BASE_REF)
            ref = await gh.ensure_proposal_issue(
                prop.sha256, title, issue_text(state, prop, patch, note), looked_up=True
            )
            ended = "issue"
            return ref, note
        finally:
            # No pile-up: the worktree and local branch always go. When it ends as an issue,
            # a branch an earlier attempt pushed goes too; after a failure it stays for the
            # retry, which force-pushes over it.
            await git.remove_worktree(path, prop.branch, remote=ended == "issue")


def _dump(verdict: Verdict) -> str:
    return json.dumps(verdict.model_dump(mode="json"), indent=2) + "\n"


def format_status(state: RunState, now: str | None = None) -> str:
    """The run's status. The plan-usage-limit line shows while the run is paused (`now` before
    `resume_at`) or after a limit episode escalated the run."""
    b = state.budget
    lines = [
        f"run {state.run_id}  kind={state.kind.value}  status={state.status.value}"
        f"  exit={state.exit_code if state.exit_code is not None else '-'}"
        f"{'  dry-run' if state.dry_run else ''}",
        f"budget: spent ${b.spent:.2f} reserved ${b.reserved:.2f} of ${b.limit:.2f}"
        f"{'  (exhausted)' if b.exhausted else ''}",
        status_line(state),
    ]
    paused = state.resume_at is not None and now is not None and now < state.resume_at
    if paused or state.limit_exceeded:
        lines.append(
            f"plan usage limit: resume_at {state.resume_at or '-'}  "
            f"episode since {state.limit_episode_start or '-'}"
            f"{'  (exceeded usage_limit_max_wait_h)' if state.limit_exceeded else ''}"
        )
    if state.status is Status.PAUSED:  # issue #72
        lines.append(f"paused at {state.paused_at or '-'} ({state.pause_kind or 'pause'})")
    elif state.pause_requested_at is not None and state.status is Status.RUNNING:
        finishing = sum(1 for r in state.steps if r.status is Status.RUNNING)
        lines.append(f"pausing ({finishing} steps finishing) since {state.pause_requested_at}")
    lines += [
        "",
        f"{'scope':<28} {'stage':<14} {'status':<10} {'round':>5}  reason",
    ]
    for u in state.units:
        lines.append(
            f"{u.scope:<28} {u.stage:<14} {u.status.value:<10} {u.round:>5}  "
            f"{u.reason or ''}".rstrip()
        )
    for e in state.escalations:
        lines.append(f"escalation: {e.scope} issue #{e.issue}: {e.reason}")
    for a in state.awaiting:  # AD-27
        lines.append(f"awaiting owner: PR #{a.pr} for {a.scope}/{a.stage} since {a.since}")
    lines += owner_review_lines(state)  # AD-26
    return "\n".join(lines)


__all__ = [
    "CoreError",
    "IssueRefused",
    "LoopError",
    "Orchestrator",
    "format_status",
    "make_run_id",
    "stories_for_from",
]
