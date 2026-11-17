"""Render step prompts from `sdlc/prompts/<step>.md` (AD-8). Pure: the template text comes in."""

from __future__ import annotations

from collections.abc import Mapping
from string import Template

PROMPT_FIELDS = (
    "run_id",
    "scope",
    "stage",
    "step",
    "round",
    "worktree",
    "verdict_path",
    "inputs",
    "story_key",
    "story_title",
    "story_sources",
    "story_touches",
    "epics_path",
    "feedback",
)


class PromptError(ValueError):
    pass


def render_prompt(template: str, context: Mapping[str, str]) -> str:
    missing = [f for f in PROMPT_FIELDS if f not in context]
    if missing:
        raise PromptError(f"prompt context lacks {', '.join(missing)}")
    try:
        return Template(template).substitute(context)
    except (KeyError, ValueError) as exc:
        raise PromptError(f"prompt template has an unknown placeholder: {exc}") from exc


def bullet_list(items: list[str] | tuple[str, ...], empty: str) -> str:
    return "\n".join(f"- {i}" for i in items) if items else empty
