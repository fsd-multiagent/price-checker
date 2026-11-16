"""core/sprint_status.py: run state rendered as BMAD sprint statuses (AD-4, AD-21)."""

from __future__ import annotations

import yaml

from sdlc.core.models import (
    GateEvaluated,
    Merged,
    RunKind,
    RunStarted,
    StoriesLoaded,
    Story,
    UnitStarted,
    WorktreeReady,
)
from sdlc.core.sprint_status import render_sprint_status, story_status
from sdlc.core.state import apply, initial_state

T = "2026-11-16T09:00:00Z"
STORIES = (
    Story(key="1-1-a", title="A", sources=()),
    Story(key="1-2-b", title="B", sources=()),
    Story(key="1-3-c", title="C", sources=()),
    Story(key="2-1-d", title="D", sources=()),
)


def state_with_progress(pipeline):
    s = initial_state(
        pipeline, run_id="r1", kind=RunKind.FULL, at=T, dry_run=True, from_stage="build"
    )
    s = apply(pipeline, s, RunStarted(at=T))
    s = apply(pipeline, s, StoriesLoaded(at=T, stories=STORIES))
    for key in ("1-1-a", "1-2-b", "1-3-c"):
        scope = f"story:{key}"
        s = apply(
            pipeline,
            s,
            WorktreeReady(at=T, scope=scope, branch="b", worktree=f"/{key}", head_sha="h"),
        )
        s = apply(pipeline, s, UnitStarted(at=T, scope=scope, stage="build"))
    # 1-1-a: built and merged. 1-2-b: built, integrating. 1-3-c: still building. 2-1-d: backlog.
    for key in ("1-1-a", "1-2-b"):
        scope = f"story:{key}"
        s = apply(
            pipeline, s, GateEvaluated(at=T, scope=scope, stage="build", round=1, verdicts=())
        )
        s = apply(pipeline, s, UnitStarted(at=T, scope=scope, stage="integrate"))
    s = apply(pipeline, s, Merged(at=T, scope="story:1-1-a", sha="m"))
    return s


def test_story_statuses(pipeline):
    s = state_with_progress(pipeline)
    assert story_status(pipeline, s, "1-1-a") == "done"
    assert story_status(pipeline, s, "1-2-b") == "review"
    assert story_status(pipeline, s, "1-3-c") == "in-progress"
    assert story_status(pipeline, s, "2-1-d") == "backlog"


def test_epic_and_retro_mapping(pipeline):
    s = state_with_progress(pipeline)
    doc = yaml.safe_load(render_sprint_status(pipeline, s))
    dev = doc["development_status"]
    assert dev["epic-1"] == "in-progress"
    assert dev["epic-2"] == "backlog"
    assert dev["epic-1-retrospective"] == "optional"
    assert list(dev)[:5] == ["epic-1", "1-1-a", "1-2-b", "1-3-c", "epic-1-retrospective"]
    assert render_sprint_status(pipeline, s).startswith("# Rendered by Zamolxe (Orchestrator)")


def test_epic_done_and_retro_done(pipeline):
    s = initial_state(
        pipeline, run_id="r1", kind=RunKind.FULL, at=T, dry_run=True, from_stage="build"
    )
    s = apply(pipeline, s, RunStarted(at=T))
    s = apply(pipeline, s, StoriesLoaded(at=T, stories=STORIES[:1]))
    s = apply(
        pipeline,
        s,
        WorktreeReady(at=T, scope="story:1-1-a", branch="b", worktree="/a", head_sha="h"),
    )
    s = apply(pipeline, s, Merged(at=T, scope="story:1-1-a", sha="m"))
    s = apply(
        pipeline,
        s,
        WorktreeReady(at=T, scope="learn:epic-1", branch="l", worktree="/e", head_sha="h"),
    )
    s = apply(pipeline, s, UnitStarted(at=T, scope="learn:epic-1", stage="retro"))
    s = apply(
        pipeline, s, GateEvaluated(at=T, scope="learn:epic-1", stage="retro", round=1, verdicts=())
    )
    dev = yaml.safe_load(render_sprint_status(pipeline, s))["development_status"]
    assert dev["epic-1"] == "done" and dev["1-1-a"] == "done"
    assert dev["epic-1-retrospective"] == "done"
