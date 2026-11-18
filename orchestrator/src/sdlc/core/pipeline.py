"""Load and validate `sdlc/pipeline.yaml` (AD-7, AD-16, AD-17, AD-19)."""

from __future__ import annotations

from string import Formatter
from typing import Any

import yaml
from pydantic import ValidationError

from sdlc.core.gates import GATE_KINDS
from sdlc.core.models import (
    OTLP_PROTOCOLS,
    OutcomeSource,
    Pipeline,
    Settings,
    Stage,
    Step,
    StepKind,
    VerdictSource,
)

# Script steps the app knows how to run. Names, not code: the app maps each one to a port call.
SCRIPTS = frozenset(
    {
        "tests",
        "terraform_plan",
        "fr_ac_check",
        "rebase",
        "open_pr",
        "ci_green",
        "merge_pr",
        "render_sprint_status",
    }
)

DEFAULT_BRANCH = {"run": "sdlc/{run_id}", "story": "story/{key}", "learn": "learn/{key}"}
BRANCH_FIELDS = frozenset({"run_id", "key"})


class PipelineError(ValueError):
    """The pipeline cannot be loaded. The message names the stage and, where relevant, the step."""


def load_pipeline(text: str) -> Pipeline:
    try:
        raw = yaml.safe_load(text)
    except yaml.YAMLError as exc:
        raise PipelineError(f"pipeline is not valid YAML: {exc}") from exc
    if not isinstance(raw, dict) or not isinstance(raw.get("stages"), list):
        raise PipelineError("pipeline must be a mapping with a 'stages' list")
    try:
        settings = Settings.model_validate(raw.get("settings") or {})
    except ValidationError as exc:
        raise PipelineError(f"settings: {_first_error(exc)}") from exc
    _check_settings(settings)

    stages: list[Stage] = []
    for i, raw_stage in enumerate(raw["stages"]):
        stages.append(_resolve_stage(raw_stage, i, stages, settings))
    if not stages:
        raise PipelineError("pipeline has no stages")
    pipeline = Pipeline(settings=settings, stages=tuple(stages))
    _check_on_fail(pipeline)
    return pipeline


def _first_error(exc: ValidationError) -> str:
    err = exc.errors()[0]
    loc = ".".join(str(p) for p in err["loc"])
    return f"{loc}: {err['msg']}" if loc else err["msg"]


def _resolve_stage(raw: Any, index: int, earlier: list[Stage], settings: Settings) -> Stage:
    if not isinstance(raw, dict) or "id" not in raw:
        raise PipelineError(f"stage #{index + 1}: must be a mapping with an 'id'")
    sid = str(raw["id"])
    where = f"stage '{sid}'"
    if any(s.id == sid for s in earlier):
        raise PipelineError(f"{where}: duplicate stage id")

    data = dict(raw)
    data["gate"] = _normalise_gate(data.get("gate"), where)
    if data.get("parallel") is True:
        data["parallel"] = [[s.get("id") for s in data.get("steps") or [] if isinstance(s, dict)]]
    elif data.get("parallel") in (None, False):
        data["parallel"] = []

    raw_steps = data.get("steps")
    if not isinstance(raw_steps, list) or not raw_steps:
        raise PipelineError(f"{where}: needs at least one step")
    steps: list[Step] = []
    for j, raw_step in enumerate(raw_steps):
        step_id = raw_step.get("id") if isinstance(raw_step, dict) else None
        step_where = f"{where} step '{step_id or f'#{j + 1}'}'"
        try:
            step = Step.model_validate(raw_step)
        except ValidationError as exc:
            raise PipelineError(f"{step_where}: {_first_error(exc)}") from exc
        if step.max_turns is not None and step.max_turns <= 0:
            raise PipelineError(f"{step_where}: max_turns must be greater than 0")
        if step.kind is StepKind.SCRIPT and (step.model or step.effort or step.max_turns):
            raise PipelineError(
                f"{step_where}: model, effort and max_turns need an agent or judge step"
            )
        steps.append(_resolve_step(step, step_where))
        _check_role(steps[-1], step_where, settings)
    data["steps"] = steps

    try:
        stage = Stage.model_validate(data)
    except ValidationError as exc:
        raise PipelineError(f"{where}: {_first_error(exc)}") from exc

    ids = [s.id for s in stage.steps]
    dupes = {i for i in ids if ids.count(i) > 1}
    if dupes:
        raise PipelineError(f"{where} step '{sorted(dupes)[0]}': duplicate step id")
    _check_parallel(stage, where)
    _check_gates(stage, where)

    if stage.learn_queue is not None and not stage.per_story:
        raise PipelineError(f"{where}: learn_queue requires per_story: true")
    if stage.stories_file and stage.family != "run":
        raise PipelineError(f"{where}: stories_file is only allowed on a run-level stage")
    if not stage.entry:
        raise PipelineError(f"{where}: entry must list at least one run kind")
    for field in ("max_rounds", "timeout_min", "max_turns"):
        value = getattr(stage, field)
        if value is not None and value <= 0:
            raise PipelineError(f"{where}: {field} must be greater than 0")
    model_steps = [s for s in stage.steps if s.kind is not StepKind.SCRIPT]
    if model_steps and stage.budget_usd <= 0:
        raise PipelineError(
            f"{where} step '{model_steps[0].id}': an agent or judge step needs budget_usd > 0"
        )
    if stage.branch is not None:
        try:
            fields = {f for _, f, _, _ in Formatter().parse(stage.branch) if f is not None}
        except ValueError as exc:
            raise PipelineError(f"{where}: bad branch template: {exc}") from exc
        unknown = fields - BRANCH_FIELDS
        if unknown:
            raise PipelineError(
                f"{where}: branch template uses unknown placeholder(s) "
                f"{', '.join(sorted(unknown))} (known: run_id, key)"
            )

    needs = stage.needs if stage.needs is not None else ((earlier[-1].id,) if earlier else ())
    for n in needs:
        if not any(s.id == n for s in earlier):
            raise PipelineError(f"{where}: needs '{n}', which is not an earlier stage")

    resolved = {
        **stage.model_dump(),
        "needs": needs,
        "on_fail": stage.on_fail or stage.id,
        "max_rounds": stage.max_rounds or settings.max_rounds,
        "timeout_min": stage.timeout_min or settings.timeout_min,
        "branch": stage.branch or DEFAULT_BRANCH[stage.family],
    }
    return Stage.model_validate(resolved)


def _normalise_gate(gate: Any, where: str) -> list[dict[str, Any]]:
    if gate is None or gate == []:
        raise PipelineError(f"{where}: needs a gate")
    items = gate if isinstance(gate, list) else [gate]
    out: list[dict[str, Any]] = []
    for g in items:
        out.append({"kind": g} if isinstance(g, str) else g)
    for g in out:
        if not isinstance(g, dict) or g.get("kind") not in GATE_KINDS:
            kind = g.get("kind") if isinstance(g, dict) else g
            raise PipelineError(
                f"{where}: unknown gate kind '{kind}' (known: {', '.join(sorted(GATE_KINDS))})"
            )
    return out


def _resolve_step(step: Step, where: str) -> Step:
    if step.kind is StepKind.SCRIPT:
        if step.script not in SCRIPTS:
            raise PipelineError(f"{where}: unknown script '{step.script}'")
    else:
        if not step.prompt:
            raise PipelineError(f"{where}: {_a(step.kind)} step needs a prompt")
        if step.kind is StepKind.AGENT and not (step.skill or step.agent):
            raise PipelineError(f"{where}: an agent step needs a skill or an agent")
    if step.kind is not StepKind.SCRIPT and not step.tools:
        raise PipelineError(f"{where}: {_a(step.kind)} step needs a tools list (AD-8)")
    if step.kind is StepKind.JUDGE and step.writes:
        raise PipelineError(f"{where}: a judge is read-only (AD-10)")
    if step.verdict_source is VerdictSource.FILE and not step.writes:
        raise PipelineError(
            f"{where}: verdict_source 'file' on a read-only step; use 'result_block' (AD-17)"
        )
    if step.kind is StepKind.JUDGE and step.verdict_source is None:
        raise PipelineError(f"{where}: a judge must declare a verdict_source (AD-17)")
    outcome = step.outcome_source
    if outcome is None:
        if step.kind is StepKind.SCRIPT:
            outcome = OutcomeSource.EXIT_CODE
        elif step.verdict_source is not None:
            outcome = OutcomeSource.VERDICT
        else:
            outcome = OutcomeSource.RESULT_STATUS
    if outcome is OutcomeSource.RESULT_STATUS and step.verdict_source is (
        VerdictSource.RESULT_BLOCK
    ):
        raise PipelineError(
            f"{where}: outcome_source 'result_status' and verdict_source 'result_block' both "
            "read the last json block; use outcome_source 'verdict'"
        )
    if outcome is OutcomeSource.VERDICT and step.verdict_source is None:
        raise PipelineError(f"{where}: outcome_source 'verdict' needs a verdict_source")
    return Step.model_validate({**step.model_dump(), "outcome_source": outcome})


def _check_settings(settings: Settings) -> None:
    """AD-24 roster shape; AD-25 telemetry: an http(s) endpoint and an OTLP protocol."""
    for role, runners in settings.agents.items():
        if not role.strip() or not runners:
            raise PipelineError(f"settings: agents: role '{role}' must list what it may run")
    t = settings.telemetry
    if t.enabled:
        if not (t.endpoint or "").startswith(("http://", "https://")):
            raise PipelineError(
                "settings: telemetry: enabled needs an http:// or https:// endpoint (AD-25)"
            )
        if t.protocol not in OTLP_PROTOCOLS:
            raise PipelineError(
                f"settings: telemetry: protocol must be one of {', '.join(OTLP_PROTOCOLS)} (AD-25)"
            )


def _check_role(step: Step, where: str, settings: Settings) -> None:
    """AD-24: every agent and judge step names a known role allowed to run it."""
    runner = step.runner
    if runner is None:
        if step.agent_role is not None:
            raise PipelineError(f"{where}: a script step has no agent_role (AD-24)")
        return
    role = step.agent_role
    if not role:
        raise PipelineError(f"{where}: {_a(step.kind)} step needs an agent_role (AD-24)")
    if role not in settings.agents:
        raise PipelineError(f"{where}: unknown agent_role '{role}' (not in settings.agents)")
    if runner not in settings.agents[role]:
        raise PipelineError(
            f"{where}: agent_role '{role}' may not run '{runner}' "
            f"(allowed: {', '.join(settings.agents[role])}) (AD-24)"
        )


def _a(kind: StepKind) -> str:
    return f"an {kind}" if kind is StepKind.AGENT else f"a {kind}"


def _check_parallel(stage: Stage, where: str) -> None:
    order = [s.id for s in stage.steps]
    seen: set[str] = set()
    for group in stage.parallel:
        if not group:
            raise PipelineError(f"{where}: empty parallel group")
        for sid in group:
            if sid not in order:
                raise PipelineError(f"{where} step '{sid}': parallel group names an unknown step")
            if sid in seen:
                raise PipelineError(f"{where} step '{sid}': step is in two parallel groups")
            seen.add(sid)
        positions = sorted(order.index(s) for s in group)
        if positions != list(range(positions[0], positions[0] + len(positions))):
            raise PipelineError(f"{where}: parallel group {list(group)} is not contiguous")
        writers = [sid for sid in group if stage.step(sid).writes]
        if len(writers) > 1:
            raise PipelineError(
                f"{where} step '{writers[1]}': two writers in one parallel group "
                f"({', '.join(writers)}); at most one step per worktree may write (AD-16)"
            )


def _check_gates(stage: Stage, where: str) -> None:
    kinds = [g.kind for g in stage.gate]
    if len(set(kinds)) != len(kinds):
        raise PipelineError(f"{where}: a gate kind appears twice")
    for g in stage.gate:
        for sid in g.steps:
            if sid not in {s.id for s in stage.steps}:
                raise PipelineError(f"{where} step '{sid}': gate '{g.kind}' names an unknown step")


def ancestors(pipeline: Pipeline, stage_id: str) -> set[str]:
    out: set[str] = set()
    todo = list(pipeline.stage(stage_id).needs or ())
    while todo:
        n = todo.pop()
        if n not in out:
            out.add(n)
            todo.extend(pipeline.stage(n).needs or ())
    return out


def _check_on_fail(pipeline: Pipeline) -> None:
    for stage in pipeline.stages:
        target = stage.on_fail or stage.id
        where = f"stage '{stage.id}'"
        if target != stage.id and target not in ancestors(pipeline, stage.id):
            raise PipelineError(
                f"{where}: on_fail '{target}' is not this stage or an earlier one in its chain"
            )
        if pipeline.stage(target).family != stage.family:
            raise PipelineError(f"{where}: on_fail '{target}' runs on a different kind of scope")
