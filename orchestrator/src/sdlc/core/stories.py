"""Which units a unit waits on, and why a unit is blocked (AD-14, AD-15, AD-20)."""

from __future__ import annotations

from sdlc.core.models import Pipeline, RunState, Status, Unit
from sdlc.core.state import unit_scope

DONE = (Status.PASSED, Status.SKIPPED)
STUCK = (Status.ESCALATED, Status.BLOCKED)


def prerequisites(pipeline: Pipeline, state: RunState, unit: Unit) -> list[Unit]:
    """The units this unit needs, from the stage's `needs`. Stages outside the run kind drop out."""
    stage = pipeline.stage(unit.stage)
    out: list[Unit] = []
    for need in stage.needs or ():
        other = pipeline.stage(need)
        if other.family == "run":
            subjects: list[str | None] = [None]
        elif stage.family == "learn" and other.family == "story":
            subjects = list(_learn_subjects(state, unit))
        else:
            subjects = [unit.subject]
        for subject in subjects:
            found = state.unit(unit_scope(other, subject), need)
            if found is not None:
                out.append(found)
    return out


def _learn_subjects(state: RunState, unit: Unit) -> list[str]:
    subject = unit.subject or ""
    if subject.startswith("epic-"):
        epic = int(subject.removeprefix("epic-"))
        return [s.key for s in state.stories if s.epic == epic]
    return [subject]


def _story_units(state: RunState, key: str) -> list[Unit]:
    return [u for u in state.units if u.scope == f"story:{key}"]


def _stuck(state: RunState, unit: Unit) -> bool:
    """Escalated, or blocked on something other than an owner review: a unit that waits for
    the owner's merge is only waited on (AD-27)."""
    if unit.status is Status.BLOCKED and state.awaited(unit.scope, unit.stage) is not None:
        return False
    return unit.status in STUCK


def block_reason(
    pipeline: Pipeline, state: RunState, unit: Unit, approved: frozenset[str]
) -> str | None:
    """Why the unit must wait for a human, or None. A blocked unit consumes no round."""
    awaited = state.awaited(unit.scope, unit.stage)
    if awaited is not None:  # AD-27: until the owner merges or closes the PR
        return f"awaiting owner review of PR #{awaited.pr}"
    for p in prerequisites(pipeline, state, unit):
        if _stuck(state, p):
            return f"waiting on {p.scope}/{p.stage}, which is {p.status}"
    stage = pipeline.stage(unit.stage)
    if stage.family != "story":
        return None
    story = state.story(unit.subject or "")
    if story is None:
        return None
    for dep in story.depends_on:
        for p in _story_units(state, dep):
            if _stuck(state, p):
                return f"depends on story {dep}, which is {p.status}"
    unapproved = [s for s in story.sources if s not in approved]
    if unapproved:
        return f"sources not Approved in the register: {', '.join(unapproved)}"
    return None


def merged(state: RunState, key: str) -> bool:
    scope = state.scope(f"story:{key}")
    if scope is not None and scope.merged_sha:
        return True
    units = _story_units(state, key)
    return bool(units) and all(u.status is Status.SKIPPED for u in units)


def waits_on(pipeline: Pipeline, state: RunState, unit: Unit) -> bool:
    """True while a prerequisite or a dependency has not finished yet."""
    if any(p.status not in DONE for p in prerequisites(pipeline, state, unit)):
        return True
    if pipeline.stage(unit.stage).family == "story":
        story = state.story(unit.subject or "")
        if story is not None and not all(merged(state, d) for d in story.depends_on):
            return True
    return False


def path_overlaps(a: str, b: str) -> bool:
    """True when one path equals the other or contains it (`api/` and `api/openapi.yaml`)."""
    a, b = a.strip().rstrip("/"), b.strip().rstrip("/")
    return a == b or a.startswith(b + "/") or b.startswith(a + "/")


def serial_touches(pipeline: Pipeline, state: RunState, key: str) -> set[str]:
    """The `serial_paths` this story touches."""
    story = state.story(key)
    if story is None:
        return set()
    return {
        p for p in pipeline.settings.serial_paths if any(path_overlaps(t, p) for t in story.touches)
    }
