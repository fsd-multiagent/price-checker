"""Canned results for the dry-run, keyed by scope, step and round (AD-2).

A scenario file (`scenario.yaml`) maps `<scope>/<step>[/r<n>[/a<k>]]` to an entry; `*` matches
any scope, and `a<k>` is the k-th attempt of the step in that round (a usage limit or a crash
reruns a step in the same round). Lookup order: exact scope, round and attempt, exact scope and
round, exact scope, then the same with `*`, then the kind default. Entry fields:

- `result`: a `claude -p --output-format json` fixture file (the real shape, AD-8)
- `status`: the status the agent reports in its result (`done`, `blocked`, `ambiguous`, `PASS`)
- `open_questions`: added to the reported status
- `verdict`: a verdict JSON file, returned as the last json block and written to the verdict path
- `writes`: `{worktree-relative path: fixture file}` written into the worktree
- `exit_code`: for script steps
- `crash`: raise `FakeCrash` to simulate a killed process
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml


class FakeCrash(BaseException):
    """Simulates the orchestrator process being killed mid-step."""


class FixtureBook:
    def __init__(self, root: str | Path, overlay: dict[str, Any] | None = None) -> None:
        self.root = Path(root)
        base = yaml.safe_load((self.root / "scenario.yaml").read_text(encoding="utf-8")) or {}
        overlay = overlay or {}
        self.defaults: dict[str, Any] = {**base.get("defaults", {}), **overlay.get("defaults", {})}
        self.steps: dict[str, Any] = {**base.get("steps", {}), **overlay.get("steps", {})}
        self.register: str = overlay.get("register", base.get("register", "source-register.md"))

    @classmethod
    def from_scenario(cls, root: str | Path, scenario: str | None) -> FixtureBook:
        overlay = None
        if scenario:
            path = Path(root) / "scenarios" / f"{scenario}.yaml"
            overlay = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        return cls(root, overlay)

    def lookup(
        self, scope: str, step: str, round_: int, kind: str, attempt: int | None = None
    ) -> dict[str, Any]:
        keys = [f"{scope}/{step}/r{round_}", f"{scope}/{step}", f"*/{step}/r{round_}", f"*/{step}"]
        if attempt is not None:
            keys.insert(2, f"*/{step}/r{round_}/a{attempt}")
            keys.insert(0, f"{scope}/{step}/r{round_}/a{attempt}")
        for key in keys:
            if key in self.steps:
                return {**self.defaults.get(kind, {}), **(self.steps[key] or {})}
        return dict(self.defaults.get(kind, {}))

    def text(self, rel: str) -> str:
        return (self.root / rel).read_text(encoding="utf-8")

    def written_file(self, rel: str) -> str | None:
        """The fixture a scenario writes to `rel` (for example epics.md), for `--from`."""
        for entry in self.steps.values():
            fixture = ((entry or {}).get("writes") or {}).get(rel)
            if fixture:
                return self.text(fixture)
        return None

    def register_text(self) -> str:
        return self.text(self.register)
