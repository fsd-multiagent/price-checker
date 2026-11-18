"""The ports: every external system sits behind one Protocol (AD-1).

Real runs use `sdlc.adapters.real.real_ports`; the dry-run and the app tests use
`sdlc.adapters.fake`. The `key` arguments exist for the fakes only.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Protocol

from sdlc.core.issues import IssueInfo, LabelEvent
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

    `key`, `verdict_source` and `attempt` describe the step for the fakes; the real adapter
    ignores them. `labels` are used only when telemetry is on (AD-25).
    """

    key: StepKey
    argv: tuple[str, ...]
    stdin: str
    cwd: str
    env: Mapping[str, str] = field(default_factory=dict)
    timeout_s: float = 1800.0
    verdict_source: str | None = None
    attempt: int = 1  # the k-th attempt of this step in this round, counted from the state
    # AD-25: `sdlc.*` telemetry resource labels (run_id, seq, agent, stage, step, scope).
    labels: Mapping[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class ProposalRef:
    """AD-26: the PR or issue that carries a proposal; `created` is False when it was found by
    its marker (in any state: a closed one is never reopened)."""

    kind: str  # "pr" | "issue"
    number: int
    created: bool = False


@dataclass(frozen=True)
class StagedChange:
    """AD-26: a staged change. `entries` is each path with its new `<mode> <blob>`, or None for
    a deletion: what the change is, whatever base it was cut from."""

    patch: str
    entries: tuple[tuple[str, str | None], ...] = ()


class AdapterError(Exception):
    """An adapter failed. The app turns it into a failed step, never a crash."""


class Claude(Protocol):
    async def run(self, inv: Invocation) -> StepResult: ...


class Git(Protocol):
    async def fetch(self) -> None: ...
    async def ensure_worktree(self, branch: str, base: str) -> tuple[str, str]: ...
    async def head(self, worktree: str) -> str: ...
    async def abort_rebase(self, worktree: str) -> bool: ...
    async def diff(self, worktree: str, since: str, exclude: tuple[str, ...] = ()) -> str: ...
    async def reset_hard(self, worktree: str, sha: str) -> None: ...
    async def restore_paths(self, worktree: str, patterns: tuple[str, ...]) -> None: ...
    async def commit_all(self, worktree: str, message: str) -> str | None: ...
    async def tag(self, name: str, sha: str) -> None: ...
    async def rebase(self, worktree: str, onto: str, key: StepKey | None = None) -> bool: ...
    async def changed_paths(self, worktree: str) -> list[str]: ...
    async def push(self, worktree: str, branch: str, force: bool = False) -> None: ...
    async def show(self, ref: str, path: str) -> str | None: ...
    # AD-26: stage `paths` (new files included) and return their binary diff against HEAD.
    async def staged_diff(self, worktree: str, paths: tuple[str, ...]) -> StagedChange: ...
    # ADR 0023: `git merge-base HEAD origin/main`, or None.
    async def branch_point(self, worktree: str) -> str | None: ...
    # AD-26: remove a proposal worktree and its local branch; `remote` also deletes the pushed one.
    async def remove_worktree(self, worktree: str, branch: str, remote: bool = False) -> None: ...
    # AD-26: `git apply --3way`; True only on exit 0 with no unmerged entries left.
    async def apply_3way(self, worktree: str, patch: str) -> bool: ...
    # AD-27: the paths the branch changes since its branch point (what its PR shows).
    async def branch_paths(self, worktree: str) -> list[str]: ...
    # AD-27: remove the `*.lock` files of a worktree's git dir that no process holds; returns
    # the paths removed.
    async def clear_stale_locks(self, worktree: str) -> list[str]: ...


class Gh(Protocol):
    async def ensure_pr(
        self, head: str, base: str, title: str, body: str, labels: tuple[str, ...] = ()
    ) -> int: ...
    async def checks_green(self, pr: int, timeout_min: int, key: StepKey | None = None) -> bool: ...
    # Issue #87: the failed jobs' logs of the CI runs on the PR head SHA ("" when none).
    async def failed_ci_log(self, pr: int, key: StepKey | None = None) -> str: ...
    # AD-26, AD-27: refuses an owner-review PR, and one touching protected or review paths
    # outside a factory story.
    async def merge(
        self,
        pr: int,
        head_sha: str,
        key: StepKey | None = None,
        factory: bool = False,
        paths: list[str] | None = None,
    ) -> str | None: ...
    async def ensure_escalation_issue(
        self, run_id: str, scope: str, title: str, body: str
    ) -> int: ...
    # AD-26: owner-review proposals, found by marker across every PR and issue state.
    async def ensure_label(self, name: str, color: str, description: str) -> None: ...
    async def find_proposal(self, sha256: str) -> ProposalRef | None: ...
    async def ensure_proposal_pr(
        self, sha256: str, head: str, title: str, body: str, looked_up: bool = False
    ) -> ProposalRef: ...
    async def ensure_proposal_issue(
        self, sha256: str, title: str, body: str, looked_up: bool = False
    ) -> ProposalRef: ...
    # AD-27: issue runs and owner-review waits.
    async def issue(self, number: int) -> IssueInfo: ...
    async def label_events(self, number: int) -> tuple[LabelEvent, ...]: ...
    async def comment_issue(self, number: int, body: str) -> None: ...
    async def label_pr(self, pr: int, name: str) -> None: ...
    # `(state, merge commit SHA, head SHA)`: state is OPEN, CLOSED or MERGED.
    async def pr_state(self, pr: int) -> tuple[str, str | None, str | None]: ...


class Commands(Protocol):
    """Project commands run by script steps: tests (T-8), `terraform plan`, the FR/AC check."""

    # AD-27: `factory` lets the story change the block; only the branch point's block runs.
    async def run(
        self, name: str, worktree: str, timeout_s: float, key: StepKey, factory: bool = False
    ) -> tuple[int, str]: ...


class Fs(Protocol):
    def read_text(self, path: str) -> str | None: ...
    def write_text(self, path: str, text: str) -> None: ...
    def remove(self, path: str) -> None: ...
    def make_dirs(self, path: str) -> None: ...


class Clock(Protocol):
    def now(self) -> str: ...
    async def sleep_until(self, until: str) -> None:
        """Return once `until` (UTC ISO) has passed. The dry-run fake returns at once (AD-1)."""
        ...


class Notifier(Protocol):
    def notify(self, title: str, message: str) -> None: ...
    async def drain(self) -> None:
        """Wait for notifications still being sent."""
        ...


@dataclass(frozen=True)
class Ports:
    claude: Claude
    git: Git
    gh: Gh
    commands: Commands
    fs: Fs
    clock: Clock
    notifier: Notifier
