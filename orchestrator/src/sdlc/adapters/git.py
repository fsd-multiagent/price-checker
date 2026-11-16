"""Rules every `git` adapter enforces (AD-12). The real subprocess adapter is deferred."""

from __future__ import annotations

from sdlc.ports import AdapterError

PROTECTED = frozenset({"main", "refs/heads/main", "origin/main"})


def refuse_main(branch: str) -> None:
    """The orchestrator never pushes to `main`; the guard hook does not cover its own git calls."""
    target = branch.strip().lstrip("+").split(":")[-1].strip().lstrip("+")
    if target in PROTECTED or target.removeprefix("refs/heads/") == "main":
        raise AdapterError("refusing to push to main (AD-12)")


def commit_message(run_id: str, scope: str, step: str, round_: int) -> str:
    return f"sdlc({run_id}): {scope}/{step} r{round_}"
