"""The loop: load -> decide -> act -> persist (spine "Design Paradigm").

The app is the only place that awaits ports. It runs agent steps as tasks on one asyncio loop
(AD-3), and every result comes back as events applied through `core.apply` and saved by the
store before the next decision (AD-4).
"""

from __future__ import annotations

import asyncio
import json
import shlex
from collections.abc import Callable
from pathlib import Path

from sdlc.adapters.claude import build_argv
from sdlc.adapters.git import commit_message
from sdlc.core import gates as core_gates
from sdlc.core.epics_parser import parse_epics
from sdlc.core.models import (
    Action,
    BlockUnit,
    BudgetExhausted,
    CoreError,
    EscalationOpened,
    EvaluateGate,
    Event,
    ExhaustBudget,
    Finish,
    GateEvaluated,
    Merged,
    OpenEscalation,
    Pipeline,
    PrOpened,
    ResetScope,
    RunFinished,
    RunKind,
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
    Verdict,
    VerdictSource,
    WorktreeReady,
)
from sdlc.core.outcome import derive_outcome
from sdlc.core.prompts import bullet_list, render_prompt
from sdlc.core.register_parser import parse_register
from sdlc.core.scheduler import decide, round_records
from sdlc.core.sprint_status import render_sprint_status
from sdlc.core.state import apply, initial_state
from sdlc.ports import Invocation, Ports, StepKey
from sdlc.store import RunStore

SPRINT_STATUS = "_bmad-output/implementation-artifacts/sprint-status.yaml"
DEFAULT_EPICS = "_bmad-output/planning-artifacts/epics.md"
MAX_TICKS = 100_000

StoriesReader = Callable[[str], str | None]


class LoopError(RuntimeError):
    pass


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
    ) -> int:
        stories = None
        if from_stage is not None:  # validate before the run dir exists
            reader = stories_reader or (lambda rel: self.ports.fs.read_text(str(self.repo / rel)))
            stories = stories_for_from(self.p, kind, from_stage, reader)
        now = self.ports.clock.now()
        run_id = self._free_run_id(make_run_id(now, kind))
        self.store = RunStore(self.runs_dir / run_id)
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
        self._apply(RunStarted(at=self.ports.clock.now()))
        if stories is not None:
            self._apply(StoriesLoaded(at=self.ports.clock.now(), stories=stories))
        return await self._loop()

    def _free_run_id(self, run_id: str) -> str:
        """Two runs started in the same second get a short suffix instead of colliding."""
        candidate, n = run_id, 1
        while (self.runs_dir / candidate).exists():
            n += 1
            candidate = f"{run_id}-{n}"
        return candidate

    async def resume(self, run_id: str) -> int:
        self.store = RunStore(self.runs_dir / run_id)
        self.state = self.store.load()
        self.echo(f"resume {run_id}")
        await self.ports.git.fetch()  # the register and the merge checks read origin/main
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
        return await self._loop()

    # ------------------------------------------------------------------ the loop

    def _apply(self, event: Event) -> None:
        assert self.state is not None and self.store is not None
        self.state = apply(self.p, self.state, event)
        self.store.append_event(event)
        self.store.save(self.state)

    async def _inputs(self) -> TickInputs:
        assert self.state is not None
        if not any(s.sources for s in self.state.stories):
            return TickInputs()
        text = await self.ports.git.show("origin/main", self.p.settings.register_path)
        return TickInputs(approved_domains=parse_register(text).approved)

    async def _loop(self) -> int:
        try:
            return await self._ticks()
        except BaseException as exc:
            await self._stop(exc)
            raise

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
                await self._do(action)
            if all(isinstance(a, StartStep) for a in actions):
                if not self._tasks:
                    raise LoopError("no progress: nothing to do and nothing running")
                done, _ = await asyncio.wait(self._tasks, return_when=asyncio.FIRST_COMPLETED)
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
                    await self.ports.git.fetch()  # AD-20: cut from a fresh origin/main
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
            case _:
                raise LoopError(f"unknown action {action!r}")

    async def _discard(self, scope_id: str, seq: int) -> str | None:
        """AD-6: save the discarded diff, then reset the worktree to the scope head."""
        assert self.state is not None and self.store is not None
        scope = self.state.scope(scope_id)
        if scope is None or scope.worktree is None or scope.head_sha is None:
            return None
        diff = await self.ports.git.diff(scope.worktree, scope.head_sha)
        await self.ports.git.reset_hard(scope.worktree, scope.head_sha)
        if not diff.strip():
            return None
        return self.store.write_artifact(f"steps/{seq}.discarded.patch", diff)

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
            "story_title": story.title if story else "(none)",
            "story_sources": ", ".join(story.sources) if story and story.sources else "(none)",
            "story_touches": ", ".join(story.touches) if story and story.touches else "(none)",
            "epics_path": self._epics_path(),
            "feedback": self._feedback(unit.feedback, worktree, key.round),
        }
        env = {"TMPDIR": f"{worktree}/.sdlc/tmp", "SDLC_RUN_ID": self.state.run_id}
        if verdict_path:
            env["SDLC_VERDICT_PATH"] = verdict_path
        argv = build_argv(
            budget_usd=cap,
            allowed_tools=step.tools,
            restrict_tools=not step.writes,
            agent=step.agent,
            model=stage.model,
        )
        return Invocation(
            key=key,
            argv=argv,
            stdin=render_prompt(template, context),
            cwd=worktree,
            env=env,
            timeout_s=float((stage.timeout_min or self.p.settings.timeout_min) * 60),
            verdict_source=step.verdict_source.value if step.verdict_source else None,
        )

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
        except Exception as exc:
            result = StepResult(subtype="error_adapter", is_error=True, errors=(repr(exc),))
        verdict_text = fs.read_text(verdict_path) if verdict_path else None
        head: str | None = None
        if self._is_story_file_reviewer(key, step):
            result = await self._check_reviewer_edits(key, worktree, result)
        else:
            head = await self._commit_if_done(key, step, worktree, result, verdict_text)
        base = f"steps/{key.seq}-{key.scope}-{key.step}"
        self.store.write_artifact(f"{base}.json", result.model_dump_json(indent=2))
        self.store.write_artifact(
            f"{base}.log",
            f"$ {shlex.join(inv.argv)}\n\n{inv.stdin}\n\n---\n{result.result or ''}\n",
        )
        for d in result.permission_denials:
            self.echo(f"        permission denied: {d.tool_name} {d.detail}")
        return [
            StepFinished(
                at=self.ports.clock.now(),
                seq=key.seq,
                result=result,
                verdict_text=verdict_text,
                head_sha=head,
            )
        ]

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
                "subtype": "error_reviewer_edited",
                "is_error": True,
                "errors": [*result.errors, why],
            }
        )

    async def _commit_if_done(
        self, key: StepKey, step: Step, worktree: str, result: StepResult, verdict_text: str | None
    ) -> str | None:
        """AD-6, AD-21: leftovers are committed only when a writing step ends `done`."""
        if not step.writes:
            return None
        if step.verdict_source is VerdictSource.RESULT_BLOCK:
            verdict = core_gates.last_verdict_block(result.result)
        else:
            verdict = core_gates.parse_verdict_text(verdict_text)
        readiness = self.p.stage(key.stage).gate_of_step(step.id).kind == "readiness"
        if derive_outcome(step, result, verdict, readiness=readiness).outcome.value != "done":
            return None
        try:
            if key.scope.startswith("story:"):
                await self.ports.git.restore_paths(worktree, self.p.settings.bookkeeping_paths)
            assert self.state is not None
            sha = await self.ports.git.commit_all(
                worktree, commit_message(self.state.run_id, key.scope, key.step, key.round)
            )
            return sha or await self.ports.git.head(worktree)
        except Exception as exc:
            self.echo(f"        commit failed: {exc!r}")
            return None

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
                    code, out = await self.ports.commands.run(step.script, worktree, timeout, key)
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
                    await git.push(worktree, scope.branch)
                    title = f"sdlc({state.run_id}): {key.scope}"
                    body = (
                        f"Opened by Zamolxe (Orchestrator) for {key.scope} in run {state.run_id}."
                    )
                    number = await gh.ensure_pr(scope.branch, "main", title, body)
                    events.append(
                        PrOpened(at=self.ports.clock.now(), scope=key.scope, number=number)
                    )
                    code, out = 0, f"PR #{number}"
                case "ci_green":
                    ok = scope.pr is not None and await gh.checks_green(
                        scope.pr, self.p.settings.ci_timeout_min, key=key
                    )
                    code, out = (0, "required checks green") if ok else (1, "checks not green")
                case "merge_pr":
                    if scope.pr is None or not scope.head_sha:
                        code, out = 1, "no PR or no head SHA to merge"
                    else:
                        sha = await gh.merge(scope.pr, scope.head_sha, key=key)
                        if sha:
                            events.append(
                                Merged(at=self.ports.clock.now(), scope=key.scope, sha=sha)
                            )
                        code, out = (0, f"merged {sha}") if sha else (1, "not merged")
                case "render_sprint_status":
                    self.ports.fs.write_text(
                        f"{worktree}/{SPRINT_STATUS}", render_sprint_status(self.p, state)
                    )
                    head = await git.commit_all(
                        worktree, commit_message(state.run_id, key.scope, key.step, key.round)
                    )
                    code, out = 0, f"rendered {SPRINT_STATUS}"
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
        sv = core_gates.evaluate_stage(stage, records, files, f"{action.scope} r{action.round}")
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


def _dump(verdict: Verdict) -> str:
    return json.dumps(verdict.model_dump(mode="json"), indent=2) + "\n"


def format_status(state: RunState) -> str:
    b = state.budget
    lines = [
        f"run {state.run_id}  kind={state.kind.value}  status={state.status.value}"
        f"  exit={state.exit_code if state.exit_code is not None else '-'}"
        f"{'  dry-run' if state.dry_run else ''}",
        f"budget: spent ${b.spent:.2f} reserved ${b.reserved:.2f} of ${b.limit:.2f}"
        f"{'  (exhausted)' if b.exhausted else ''}",
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
    return "\n".join(lines)


__all__ = [
    "CoreError",
    "LoopError",
    "Orchestrator",
    "format_status",
    "make_run_id",
    "stories_for_from",
]
