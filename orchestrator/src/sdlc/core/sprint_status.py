"""Render `sprint-status.yaml` from the run state (AD-4, AD-21). Pure."""

from __future__ import annotations

import yaml

from sdlc.core.models import Pipeline, RunState, Status


def story_status(pipeline: Pipeline, state: RunState, key: str) -> str:
    """Map run state to the BMAD story statuses (backlog, in-progress, review, done)."""
    scope = state.scope(f"story:{key}")
    if scope is not None and scope.merged_sha:
        return "done"
    units = [u for u in state.units if u.scope == f"story:{key}"]
    if any(u.status is Status.RUNNING for u in units):
        last = [u for u in units if u.status is Status.RUNNING][-1]
        first_story_stage = units[0].stage if units else None
        return "in-progress" if last.stage == first_story_stage else "review"
    if any(u.status is Status.PASSED for u in units):
        return "review"
    return "backlog"


def render_sprint_status(pipeline: Pipeline, state: RunState) -> str:
    epics = sorted({s.epic for s in state.stories})
    dev: dict[str, str] = {}
    for n in epics:
        keys = [s.key for s in state.stories if s.epic == n]
        statuses = [story_status(pipeline, state, k) for k in keys]
        if all(x == "done" for x in statuses):
            dev[f"epic-{n}"] = "done"
        elif any(x != "backlog" for x in statuses):
            dev[f"epic-{n}"] = "in-progress"
        else:
            dev[f"epic-{n}"] = "backlog"
        for k, st in zip(keys, statuses, strict=True):
            dev[k] = st
        retro = state.unit(f"learn:epic-{n}", _retro_stage(pipeline) or "")
        dev[f"epic-{n}-retrospective"] = (
            "done" if retro is not None and retro.status is Status.PASSED else "optional"
        )
    doc = {
        "generated": state.created_at,
        "project": "price-checker",
        "tracking_system": "file-system",
        "story_location": "_bmad-output/implementation-artifacts",
        "development_status": dev,
    }
    header = f"# Rendered by Zamolxe (Orchestrator) from run {state.run_id}; do not edit.\n"
    return header + yaml.safe_dump(doc, sort_keys=False)


def _retro_stage(pipeline: Pipeline) -> str | None:
    for s in pipeline.stages:
        if s.learn_queue is not None and s.learn_queue.value == "epic":
            return s.id
    return None
