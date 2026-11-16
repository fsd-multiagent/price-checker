"""The ports: every external system sits behind one Protocol (AD-1).

Real adapters for `claude`, `git`, `gh` and the notifier are deferred (deferred-work.md); the
dry-run and the tests use `sdlc.adapters.fake`.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Protocol

from sdlc.core.models import StepResult


@dataclass(frozen=True)
class StepKey:
    seq: int
    scope: str
    stage: str
    step: str
    round: int


@dataclass(frozen=True)
class Invocation:
    """One `claude -p` process (AD-8).

    `key` and `verdict_source` describe the step for the fakes; the real adapter ignores both.
    """

    key: StepKey
    argv: tuple[str, ...]
    stdin: str
    cwd: str
    env: Mapping[str, str] = field(default_factory=dict)
    timeout_s: float = 1800.0
    verdict_source: str | None = None


class AdapterError(Exception):
    """An adapter failed. The app turns it into a failed step, never a crash."""


class Claude(Protocol):
    async def run(self, inv: Invocation) -> StepResult: ...


class Git(Protocol):
    async def fetch(self) -> None: ...
    async def ensure_worktree(self, branch: str, base: str) -> tuple[str, str]: ...
    async def head(self, worktree: str) -> str: ...
    async def abort_rebase(self, worktree: str) -> bool: ...
    async def diff(self, worktree: str, since: str) -> str: ...
    async def reset_hard(self, worktree: str, sha: str) -> None: ...
    async def restore_paths(self, worktree: str, patterns: tuple[str, ...]) -> None: ...
    async def commit_all(self, worktree: str, message: str) -> str | None: ...
    async def tag(self, name: str, sha: str) -> None: ...
    async def rebase(self, worktree: str, onto: str, key: StepKey | None = None) -> bool: ...
    async def changed_paths(self, worktree: str) -> list[str]: ...
    async def push(self, worktree: str, branch: str) -> None: ...
    async def show(self, ref: str, path: str) -> str | None: ...


class Gh(Protocol):
    async def ensure_pr(self, head: str, base: str, title: str, body: str) -> int: ...
    async def checks_green(self, pr: int, timeout_min: int, key: StepKey | None = None) -> bool: ...
    async def merge(self, pr: int, head_sha: str, key: StepKey | None = None) -> str | None: ...
    async def ensure_escalation_issue(
        self, run_id: str, scope: str, title: str, body: str
    ) -> int: ...


class Commands(Protocol):
    """Project commands run by script steps: tests (T-8), `terraform plan`, the FR/AC check."""

    async def run(
        self, name: str, worktree: str, timeout_s: float, key: StepKey
    ) -> tuple[int, str]: ...


class Fs(Protocol):
    def read_text(self, path: str) -> str | None: ...
    def write_text(self, path: str, text: str) -> None: ...
    def remove(self, path: str) -> None: ...
    def make_dirs(self, path: str) -> None: ...


class Clock(Protocol):
    def now(self) -> str: ...


class Notifier(Protocol):
    def notify(self, title: str, message: str) -> None: ...


@dataclass(frozen=True)
class Ports:
    claude: Claude
    git: Git
    gh: Gh
    commands: Commands
    fs: Fs
    clock: Clock
    notifier: Notifier
