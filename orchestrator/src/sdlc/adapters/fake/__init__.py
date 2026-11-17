"""Fake adapters for the dry-run and the tests (AD-2). They record calls and mutate nothing real."""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from sdlc.adapters.claude import parse_result
from sdlc.adapters.fake.fixtures import FakeCrash, FixtureBook
from sdlc.adapters.git import refuse_main
from sdlc.core.models import StepResult
from sdlc.ports import Invocation, Ports, StepKey

__all__ = [
    "FakeClaude",
    "FakeClock",
    "FakeCommands",
    "FakeCrash",
    "FakeFs",
    "FakeGh",
    "FakeGit",
    "FakeNotifier",
    "FixtureBook",
    "fake_ports",
]


def _sha(seed: str) -> str:
    return hashlib.sha1(seed.encode(), usedforsecurity=False).hexdigest()


class FakeClock:
    """Starts at a fixed instant and moves one second per call (T-5).

    `sleep_until` returns at once and moves the clock to `until` (AD-1).
    """

    def __init__(self, start: str = "2026-11-16T09:00:00Z") -> None:
        self._t = datetime.strptime(start, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=UTC)
        self.sleeps: list[str] = []

    def now(self) -> str:
        self._t += timedelta(seconds=1)
        return self._t.strftime("%Y-%m-%dT%H:%M:%SZ")

    async def sleep_until(self, until: str) -> None:
        self.sleeps.append(until)
        target = datetime.strptime(until, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=UTC)
        self._t = max(self._t, target)


class FakeFs:
    """Writes stay in memory; reads fall back to the real disk (the repo and the run dir)."""

    def __init__(self) -> None:
        self.files: dict[str, str] = {}
        self.dirs: set[str] = set()
        self.log: list[str] = []  # every path written, in order; FakeGit reads it for diffs

    def read_text(self, path: str) -> str | None:
        if path in self.files:
            return self.files[path]
        try:
            return Path(path).read_text(encoding="utf-8")
        except (FileNotFoundError, IsADirectoryError, NotADirectoryError):
            return None

    def write_text(self, path: str, text: str) -> None:
        self.files[path] = text
        self.log.append(path)

    def remove(self, path: str) -> None:
        self.files.pop(path, None)

    def make_dirs(self, path: str) -> None:
        self.dirs.add(path)


class FakeNotifier:
    def __init__(self) -> None:
        self.sent: list[tuple[str, str]] = []

    def notify(self, title: str, message: str) -> None:
        self.sent.append((title, message))

    async def drain(self) -> None:
        return None


class FakeClaude:
    """Returns canned results in the real `claude -p` JSON shape, parsed by the real parser."""

    def __init__(self, book: FixtureBook, fs: FakeFs) -> None:
        self.book = book
        self.fs = fs
        self.invocations: list[Invocation] = []

    async def run(self, inv: Invocation) -> StepResult:
        self.invocations.append(inv)
        k = inv.key
        verdict_path = inv.env.get("SDLC_VERDICT_PATH")
        reviewer = inv.verdict_source is not None
        # The attempt comes from the run state, so a resumed run does not replay attempt 1.
        entry = self.book.lookup(
            k.scope, k.step, k.round, "reviewer" if reviewer else "agent", attempt=inv.attempt
        )
        if entry.get("crash"):
            raise FakeCrash(f"simulated kill during {k.scope}/{k.step} r{k.round}")
        for rel, fixture in (entry.get("writes") or {}).items():
            self.fs.write_text(str(Path(inv.cwd) / rel), self.book.text(fixture))
        data: dict[str, Any] = json.loads(
            self.book.text(entry.get("result", "results/success.json"))
        )
        if data.get("subtype") == "success":
            data["result"] = self._result_text(entry, inv.verdict_source, verdict_path)
        if "cost" in entry:
            data["total_cost_usd"] = entry["cost"]
        return parse_result(json.dumps(data))

    def _result_text(
        self, entry: dict[str, Any], verdict_source: str | None, verdict_path: str | None
    ) -> str:
        parts = ["Dry-run fixture result."]
        if "status" in entry or verdict_source is None:
            status: dict[str, Any] = {"status": entry.get("status", "done")}
            if entry.get("open_questions"):
                status["open_questions"] = entry["open_questions"]
            parts.append("```json\n" + json.dumps(status) + "\n```")
        if verdict_source and entry.get("verdict"):
            verdict = self.book.text(entry["verdict"])
            if verdict_source == "file" and verdict_path:
                self.fs.write_text(verdict_path, verdict)
            else:
                parts.append("```json\n" + verdict.strip() + "\n```")
        return "\n\n".join(parts)


class FakeCommands:
    def __init__(self, book: FixtureBook) -> None:
        self.book = book
        self.calls: list[tuple[str, str]] = []

    async def run(
        self, name: str, worktree: str, timeout_s: float, key: StepKey
    ) -> tuple[int, str]:
        self.calls.append((name, worktree))
        entry = self.book.lookup(key.scope, key.step, key.round, "script")
        if entry.get("crash"):
            raise FakeCrash(f"simulated kill during {key.scope}/{key.step}")
        code = int(entry.get("exit_code", 0))
        return code, f"fake {name}: exit {code}"


class FakeGit:
    def __init__(self, book: FixtureBook, root: str, fs: FakeFs | None = None) -> None:
        self.book = book
        self.root = root
        self.fs = fs or FakeFs()
        self._clean_at: dict[str, int] = {}  # worktree -> fs.log length at the last commit/reset
        self.calls: list[tuple[Any, ...]] = []
        self.heads: dict[str, str] = {"origin/main": _sha("origin/main")}
        self.worktrees: dict[str, str] = {}
        self.tags: dict[str, str] = {}
        self._n = 0

    def _branch(self, worktree: str) -> str:
        """A resumed run gets a fresh fake: unknown worktrees are adopted from their path."""
        branch = self.worktrees.setdefault(worktree, worktree.split("/worktrees/", 1)[-1])
        self.heads.setdefault(branch, self.heads["origin/main"])
        return branch

    def _new_sha(self, seed: str) -> str:
        self._n += 1
        return _sha(f"{seed}:{self._n}")

    async def fetch(self) -> None:
        self.calls.append(("fetch",))

    async def ensure_worktree(self, branch: str, base: str) -> tuple[str, str]:
        path = f"{self.root}/worktrees/{branch}"
        if path not in self.worktrees:
            self.worktrees[path] = branch
            self.heads.setdefault(branch, self.heads.get(base, _sha(base)))
        self.calls.append(("ensure_worktree", branch, base))
        return path, self.heads[branch]

    async def head(self, worktree: str) -> str:
        return self.heads[self._branch(worktree)]

    async def abort_rebase(self, worktree: str) -> bool:
        self.calls.append(("abort_rebase", worktree))
        return False

    async def diff(self, worktree: str, since: str) -> str:
        self.calls.append(("diff", worktree, since))
        return f"# fake diff of {worktree} since {since}\n"

    async def reset_hard(self, worktree: str, sha: str) -> None:
        self.calls.append(("reset_hard", worktree, sha))
        self.heads[self._branch(worktree)] = sha
        self._clean_at[worktree] = len(self.fs.log)

    async def restore_paths(self, worktree: str, patterns: tuple[str, ...]) -> None:
        self.calls.append(("restore_paths", worktree, patterns))

    async def commit_all(self, worktree: str, message: str) -> str | None:
        branch = self._branch(worktree)
        sha = self._new_sha(branch)
        self.heads[branch] = sha
        self._clean_at[worktree] = len(self.fs.log)
        self.calls.append(("commit", worktree, message, sha))
        return sha

    async def tag(self, name: str, sha: str) -> None:
        self.tags[name] = sha
        self.calls.append(("tag", name, sha))

    async def changed_paths(self, worktree: str) -> list[str]:
        """Paths written in the worktree since its last commit or reset (gitignored or not)."""
        prefix = worktree.rstrip("/") + "/"
        since = self.fs.log[self._clean_at.get(worktree, 0) :]
        return sorted({p.removeprefix(prefix) for p in since if p.startswith(prefix)})

    async def rebase(self, worktree: str, onto: str, key: StepKey | None = None) -> bool:
        self.calls.append(("rebase", worktree, onto))
        return _script_ok(self.book, key)

    async def push(self, worktree: str, branch: str) -> None:
        refuse_main(branch)
        self.calls.append(("push", branch, self.heads[self._branch(worktree)]))

    async def show(self, ref: str, path: str) -> str | None:
        self.calls.append(("show", ref, path))
        if path.endswith("source-register.md"):
            return self.book.register_text()
        return None


class FakeGh:
    def __init__(self, book: FixtureBook | None = None) -> None:
        self.book = book
        self.calls: list[tuple[Any, ...]] = []
        self.prs: dict[str, int] = {}
        self.issues: list[dict[str, Any]] = []
        self._next = 100

    async def ensure_pr(self, head: str, base: str, title: str, body: str) -> int:
        self.calls.append(("ensure_pr", head, base))
        if head not in self.prs:  # AD-13: PRs are keyed by head branch
            self._next += 1
            self.prs[head] = self._next
        return self.prs[head]

    async def checks_green(self, pr: int, timeout_min: int, key: StepKey | None = None) -> bool:
        self.calls.append(("checks_green", pr))
        return _script_ok(self.book, key)

    async def merge(self, pr: int, head_sha: str, key: StepKey | None = None) -> str | None:
        self.calls.append(("merge", pr, head_sha))
        if not _script_ok(self.book, key):
            return None
        return _sha(f"merge:{pr}:{head_sha}")

    async def ensure_escalation_issue(self, run_id: str, scope: str, title: str, body: str) -> int:
        marker = f"<!-- sdlc:{run_id}:{scope} -->"
        self.calls.append(("ensure_escalation_issue", scope))
        for issue in self.issues:  # AD-13: matched by marker, never through search
            if marker in issue["body"]:
                return int(issue["number"])
        self._next += 1
        self.issues.append({"number": self._next, "title": title, "body": f"{marker}\n{body}"})
        return self._next


def _script_ok(book: FixtureBook | None, key: StepKey | None) -> bool:
    """A script entry with a non-zero `exit_code` makes the fake rebase, CI or merge fail."""
    if book is None or key is None:
        return True
    return int(book.lookup(key.scope, key.step, key.round, "script").get("exit_code", 0)) == 0


def fake_ports(book: FixtureBook, root: str) -> Ports:
    fs = FakeFs()
    return Ports(
        claude=FakeClaude(book, fs),
        git=FakeGit(book, root, fs),
        gh=FakeGh(book),
        commands=FakeCommands(book),
        fs=fs,
        clock=FakeClock(),
        notifier=FakeNotifier(),
    )
