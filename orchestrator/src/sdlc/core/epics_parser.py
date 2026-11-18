"""Parse story entries: one fenced `yaml` block per story (AD-15).

The same parser reads `epics.md` and, later, the run-local `stories.yaml` and issue bodies.
"""

from __future__ import annotations

import re
from typing import Any, NamedTuple

import yaml

from sdlc.core.models import Story
from sdlc.core.register_parser import norm_domain
from sdlc.core.text import fenced_blocks

STORY_KEY = re.compile(r"\d+-\d+-[a-z0-9]+(?:-[a-z0-9]+)*")
REQUIRED = ("key", "title", "depends_on", "touches", "sources")


class ParsedStories(NamedTuple):
    stories: tuple[Story, ...]
    errors: tuple[str, ...]


def parse_epics(text: str) -> ParsedStories:
    """`epics.md`: epic 0 is reserved for stories from issues (AD-27)."""
    blocks = fenced_blocks(text, "yaml")
    parsed = parse_story_blocks(blocks)
    reserved = [
        f"story '{s.key}': epic 0 is reserved for issue runs (AD-27)"
        for s in parsed.stories
        if s.epic == 0
    ]
    return ParsedStories(parsed.stories, (*parsed.errors, *reserved))


def parse_story_blocks(blocks: list[str]) -> ParsedStories:
    errors: list[str] = []
    stories: list[Story] = []
    for n, body in enumerate(blocks, 1):
        story, problems = _parse_one(body, n)
        errors.extend(problems)
        if story is not None:
            stories.append(story)
    if not blocks:
        errors.append("no story blocks found (one fenced yaml block per story)")

    keys = [s.key for s in stories]
    for key in sorted({k for k in keys if keys.count(k) > 1}):
        errors.append(f"story '{key}': duplicate key")
    known = set(keys)
    for s in stories:
        for dep in s.depends_on:
            if dep not in known:
                errors.append(f"story '{s.key}': depends_on unknown story '{dep}'")
    if not errors:
        cycle = _find_cycle(stories)
        if cycle:
            errors.append(f"story '{cycle[0]}': dependency cycle {' -> '.join(cycle)}")
    return ParsedStories(tuple(stories), tuple(errors))


def load_block(body: str, n: int) -> tuple[dict[str, Any] | None, list[str]]:
    """One block's YAML mapping, or the problems."""
    try:
        data: Any = yaml.safe_load(body)
    except yaml.YAMLError as exc:
        return None, [f"story block #{n}: invalid YAML ({exc.__class__.__name__})"]
    if not isinstance(data, dict):
        return None, [f"story block #{n}: must be a mapping"]
    return data, []


def _parse_one(body: str, n: int) -> tuple[Story | None, list[str]]:
    data, problems = load_block(body, n)
    if data is None:
        return None, problems
    return story_from(data, n)


def story_from(data: dict[str, Any], n: int) -> tuple[Story | None, list[str]]:
    """The shared schema of a story entry (AD-15): `key`, `title`, `depends_on`, `touches`,
    `sources`; unknown fields are ignored."""
    key = data.get("key")
    name = f"story '{key}'" if key else f"story block #{n}"
    problems = [f"{name}: missing '{field}'" for field in REQUIRED if field not in data]
    if key is not None and not (isinstance(key, str) and STORY_KEY.fullmatch(key)):
        problems.append(f"{name}: key must match N-M-slug")
    for field in ("depends_on", "touches", "sources"):
        value = data.get(field)
        if field not in data:
            continue
        if field == "sources" and value is None:
            problems.append(f"{name}: 'sources' must be a list (use [] for none)")
        elif value is not None and not isinstance(value, list):
            problems.append(f"{name}: '{field}' must be a list")
    if problems:
        return None, problems
    story = Story(
        key=key,
        title=str(data["title"]),
        depends_on=tuple(str(d) for d in data["depends_on"] or ()),
        touches=tuple(str(t) for t in data["touches"] or ()),
        sources=tuple(norm_domain(str(s)) for s in data["sources"]),
    )
    return story, []


def _find_cycle(stories: list[Story]) -> list[str] | None:
    deps = {s.key: s.depends_on for s in stories}
    state: dict[str, int] = {}

    def visit(k: str, path: list[str]) -> list[str] | None:
        if state.get(k) == 1:
            return [*path[path.index(k) :], k]
        if state.get(k) == 2:
            return None
        state[k] = 1
        for d in deps.get(k, ()):
            found = visit(d, [*path, k])
            if found:
                return found
        state[k] = 2
        return None

    for s in stories:
        found = visit(s.key, [])
        if found:
            return found
    return None
