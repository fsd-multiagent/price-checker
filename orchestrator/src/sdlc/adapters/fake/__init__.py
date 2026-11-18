"""Fake adapters for the dry-run and the tests (AD-2). They record calls and mutate nothing real."""

from __future__ import annotations

import fnmatch
import hashlib
import json
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import yaml

from sdlc.adapters.claude import parse_result
from sdlc.adapters.fake.fixtures import FakeCrash, FixtureBook
from sdlc.adapters.gh import refuse_paths
from sdlc.adapters.git import refuse_main
from sdlc.core.issues import IssueInfo, LabelEvent
from sdlc.core.models import Settings, StepResult
from sdlc.core.proposals import OWNER_REVIEW_LABEL, in_tier, proposal_marker
from sdlc.ports import AdapterError, Invocation, Ports, ProposalRef, StagedChange, StepKey

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
        self.factory_calls: list[tuple[str, str]] = []  # AD-27: factory-story gate runs

    async def run(
        self, name: str, worktree: str, timeout_s: float, key: StepKey, factory: bool = False
    ) -> tuple[int, str]:
        self.calls.append((name, worktree))
        if factory:
            self.factory_calls.append((name, worktree))
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
        self.committed: dict[str, list[str]] = {}  # sha -> worktree-relative paths committed
        self.applied: list[tuple[str, str]] = []  # (worktree, patch) applied with --3way
        self.apply_ok = True  # False simulates a conflict in `apply_3way`
        self.show_files: dict[str, str] = {}  # files `show` returns at any ref, by path
        self.show_at: dict[tuple[str, str], str | None] = {}  # (ref, path) overrides
        self.point = "branch-point"  # what `branch_point` returns (ADR 0023)
        self.branch_files: dict[str, set[str]] = {}  # branch -> paths committed on it (AD-27)
        self.pushed: dict[str, str] = {}  # branch -> the SHA last pushed
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

    async def diff(self, worktree: str, since: str, exclude: tuple[str, ...] = ()) -> str:
        self.calls.append(("diff", worktree, since, exclude))
        out = f"# fake diff of {worktree} since {since}\n"
        for rel in await self.changed_paths(worktree):
            if not in_tier(rel, exclude) and not rel.startswith(".sdlc/"):
                out += f"+++ b/{rel}\n"
        return out

    async def reset_hard(self, worktree: str, sha: str) -> None:
        self.calls.append(("reset_hard", worktree, sha))
        self.heads[self._branch(worktree)] = sha
        self._clean_at[worktree] = len(self.fs.log)

    async def restore_paths(self, worktree: str, patterns: tuple[str, ...]) -> None:
        self.calls.append(("restore_paths", worktree, patterns))
        self._forget(worktree, lambda rel: any(fnmatch.fnmatchcase(rel, p) for p in patterns))

    def _forget(self, worktree: str, match: Any) -> None:
        """Files written in the worktree go back: the fake has no content, so they vanish."""
        prefix = worktree.rstrip("/") + "/"
        for path in [p for p in self.fs.files if p.startswith(prefix)]:
            if match(path.removeprefix(prefix)):
                del self.fs.files[path]

    async def staged_diff(self, worktree: str, paths: tuple[str, ...]) -> StagedChange:
        """A deterministic stand-in for the binary diff: each path and its content. The entries
        hash the content alone, as the real adapter's blob ids do."""
        self.calls.append(("staged_diff", worktree, paths))
        out, entries = [], []
        for rel in sorted(set(paths)):
            text = self.fs.files.get(f"{worktree.rstrip('/')}/{rel}")
            blob = None if text is None else f"100644 {_sha(text)}"
            entries.append((rel, blob))
            body = "".join(f"+{line}\n" for line in (text or "").splitlines())
            out.append(f"diff --git a/{rel} b/{rel}\n--- a/{rel}\n+++ b/{rel}\n{body}")
        return StagedChange(patch="".join(out), entries=tuple(entries))

    async def branch_point(self, worktree: str) -> str | None:
        self.calls.append(("branch_point", worktree))
        return self.point

    async def remove_worktree(self, worktree: str, branch: str, remote: bool = False) -> None:
        self.calls.append(("remove_worktree", worktree, branch, remote))
        self.worktrees.pop(worktree, None)

    async def apply_3way(self, worktree: str, patch: str) -> bool:
        self.calls.append(("apply_3way", worktree))
        if self.apply_ok:
            self.applied.append((worktree, patch))
        return self.apply_ok

    async def clear_stale_locks(self, worktree: str) -> list[str]:
        self.calls.append(("clear_stale_locks", worktree))
        return []

    async def branch_paths(self, worktree: str) -> list[str]:
        self.calls.append(("branch_paths", worktree))
        return sorted(self.branch_files.get(self._branch(worktree), ()))

    async def commit_all(self, worktree: str, message: str) -> str | None:
        branch = self._branch(worktree)
        committed = await self.changed_paths(worktree)
        sha = self._new_sha(branch)
        self.committed[sha] = committed
        self.branch_files.setdefault(branch, set()).update(committed)
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
        return sorted(
            {
                p.removeprefix(prefix)
                for p in since
                if p.startswith(prefix) and p in self.fs.files  # a restored path is unchanged
            }
        )

    async def rebase(self, worktree: str, onto: str, key: StepKey | None = None) -> bool:
        self.calls.append(("rebase", worktree, onto))
        return _script_ok(self.book, key)

    async def push(self, worktree: str, branch: str, force: bool = False) -> None:
        refuse_main(branch)
        if force and not branch.startswith("proposal/"):
            raise AdapterError(f"refusing to force-push {branch}")
        self.calls.append(("push", branch, self.heads[self._branch(worktree)], force))
        self.pushed[branch] = self.heads[self._branch(worktree)]
        # Kept on disk, so a later dry-run process (the owner's merge) still knows the head.
        pushed = Path(self.root) / "fake-git-pushed.json"
        if pushed.parent.is_dir():
            known = json.loads(pushed.read_text(encoding="utf-8")) if pushed.is_file() else {}
            pushed.write_text(json.dumps({**known, **self.pushed}), encoding="utf-8")

    def pushed_head(self, branch: str) -> str | None:
        if branch in self.pushed:
            return self.pushed[branch]
        path = Path(self.root) / "fake-git-pushed.json"
        if path.is_file():
            return json.loads(path.read_text(encoding="utf-8")).get(branch)
        return None

    async def show(self, ref: str, path: str) -> str | None:
        self.calls.append(("show", ref, path))
        if (ref, path) in self.show_at:
            return self.show_at[(ref, path)]
        if path in self.show_files:
            return self.show_files[path]
        if path.endswith("source-register.md"):
            return self.book.register_text()
        return None


_TIERS = Settings(run_budget_usd=1)


class FakeGh:
    """`issues` come from `<fixtures>/issues/<n>.yaml` (title, body, author, labels,
    last_edited_at, editor, timeline) unless a test sets `issue_data`. PR states for the
    owner-review scan come from `pr_states`, overlaid by `<runs dir>/fake-gh.json`
    (`{"pr_states": {"<n>": "MERGED"}}`), which simulates the owner on a dry-run."""

    def __init__(
        self,
        book: FixtureBook | None = None,
        state_file: str | Path | None = None,
        files_of: Callable[[str], list[str]] | None = None,
        settings: Settings | None = None,
    ) -> None:
        self.book = book
        self.tiers = settings or _TIERS
        self.state_file = Path(state_file) if state_file else None
        self.files_of = files_of or (lambda head: [])
        self.issue_data: dict[int, dict[str, Any]] = {}
        self.pr_states: dict[int, str] = {}
        self.pr_heads: dict[int, str] = {}
        self.head_of: Callable[[int], str | None] = lambda pr: None
        self.comments: list[tuple[int, str]] = []
        self.pr_bodies: dict[int, str] = {}
        self.calls: list[tuple[Any, ...]] = []
        self.prs: dict[str, int] = {}
        self.ci_logs: dict[int, str] = {}  # PR -> failed CI log (issue #87)
        self.ci_log_error = False
        self.issues: list[dict[str, Any]] = []
        self.labels: dict[int, set[str]] = {}  # PR or issue number -> labels
        self.label_names: set[str] = set()
        self.proposal_items: list[dict[str, Any]] = []  # owner-review PRs and issues (AD-26)
        self._next = 100

    async def ensure_pr(
        self, head: str, base: str, title: str, body: str, labels: tuple[str, ...] = ()
    ) -> int:
        self.calls.append(("ensure_pr", head, base))
        if head not in self.prs:  # AD-13: PRs are keyed by head branch
            self._next += 1
            self.prs[head] = self._next
            self.pr_bodies[self._next] = body
        if self.state_file is not None and self.state_file.parent.is_dir():  # later processes
            self.state_file.with_name("fake-gh-prs.json").write_text(json.dumps(self.prs))
        self.labels.setdefault(self.prs[head], set()).update(labels)  # set at creation
        self.label_names.update(labels)
        return self.prs[head]

    async def checks_green(self, pr: int, timeout_min: int, key: StepKey | None = None) -> bool:
        self.calls.append(("checks_green", pr))
        return _script_ok(self.book, key)

    async def failed_ci_log(self, pr: int, key: StepKey | None = None) -> str:
        """The `ci_log` text of the step's script entry, else `ci_logs[pr]`; an entry
        `ci_log_error: true` (or `ci_log_error` set here) makes the fetch raise."""
        self.calls.append(("failed_ci_log", pr))
        entry = (
            self.book.lookup(key.scope, key.step, key.round, "script")
            if self.book is not None and key is not None
            else {}
        )
        if self.ci_log_error or entry.get("ci_log_error"):
            raise AdapterError(f"gh run view failed for PR #{pr}")
        return str(entry.get("ci_log") or self.ci_logs.get(pr, ""))

    async def merge(
        self,
        pr: int,
        head_sha: str,
        key: StepKey | None = None,
        factory: bool = False,
        paths: list[str] | None = None,
    ) -> str | None:
        self.calls.append(("merge", pr, head_sha))
        if OWNER_REVIEW_LABEL in self.labels.get(pr, set()):
            raise AdapterError(f"refusing to merge PR #{pr}: labelled {OWNER_REVIEW_LABEL}")
        if not factory:
            head = next((h for h, n in self.prs.items() if n == pr), None)
            files = paths if paths is not None else (self.files_of(head) if head else [])
            refuse_paths(pr, files, self.tiers.protected_paths, self.tiers.review_paths)
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

    async def ensure_label(self, name: str, color: str, description: str) -> None:
        self.calls.append(("ensure_label", name))
        self.label_names.add(name)

    async def find_proposal(self, sha256: str) -> ProposalRef | None:
        self.calls.append(("find_proposal", sha256))
        marker = proposal_marker(sha256)
        for item in self.proposal_items:  # every state: closed or merged ones count too
            if marker in item["body"]:
                return ProposalRef(kind=item["kind"], number=item["number"])
        return None

    async def ensure_proposal_pr(
        self, sha256: str, head: str, title: str, body: str, looked_up: bool = False
    ) -> ProposalRef:
        self.calls.append(("ensure_proposal_pr", sha256, head))
        return await self._proposal_item("pr", sha256, title, body, head, looked_up)

    async def ensure_proposal_issue(
        self, sha256: str, title: str, body: str, looked_up: bool = False
    ) -> ProposalRef:
        self.calls.append(("ensure_proposal_issue", sha256))
        return await self._proposal_item("issue", sha256, title, body, None, looked_up)

    async def _proposal_item(
        self, kind: str, sha256: str, title: str, body: str, head: str | None, looked_up: bool
    ) -> ProposalRef:
        found = None if looked_up else await self.find_proposal(sha256)
        if found is not None:
            return found
        await self.ensure_label(OWNER_REVIEW_LABEL, "D93F0B", "owner review")  # like `--force`
        self._next += 1
        item = {"kind": kind, "number": self._next, "title": title, "head": head,
                "body": f"{proposal_marker(sha256)}\n{body}", "state": "OPEN"}  # fmt: skip
        self.proposal_items.append(item)
        self.labels[self._next] = {OWNER_REVIEW_LABEL}
        if head is not None:
            self.prs[head] = self._next
        return ProposalRef(kind=kind, number=self._next, created=True)

    # ------------------------------------------------------------------ AD-27

    def _issue_raw(self, number: int) -> dict[str, Any]:
        if number in self.issue_data:
            return self.issue_data[number]
        if self.book is not None:
            path = self.book.root / "issues" / f"{number}.yaml"
            if path.is_file():
                return yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        raise AdapterError(f"gh issue view {number}: no such issue")

    async def issue(self, number: int) -> IssueInfo:
        self.calls.append(("issue", number))
        raw = self._issue_raw(number)
        return IssueInfo(
            number=number,
            title=str(raw.get("title", "")),
            body=str(raw.get("body", "")),
            author=str(raw.get("author", "")),
            state=str(raw.get("state", "OPEN")),
            labels=tuple(raw.get("labels") or ()),
            last_edited_at=raw.get("last_edited_at"),
            editor=raw.get("editor"),
        )

    async def label_events(self, number: int) -> tuple[LabelEvent, ...]:
        self.calls.append(("label_events", number))
        return tuple(
            LabelEvent(id=str(e["id"]), event=e["event"], label=e["label"], actor=e.get("actor"))
            for e in self._issue_raw(number).get("timeline") or ()
        )

    async def comment_issue(self, number: int, body: str) -> None:
        self.calls.append(("comment_issue", number))
        self.comments.append((number, body))
        if self.state_file is not None:  # visible to a test after a CLI dry-run
            log = self.state_file.with_name("fake-gh-comments.jsonl")
            log.parent.mkdir(parents=True, exist_ok=True)
            with log.open("a", encoding="utf-8") as f:
                f.write(json.dumps({"issue": number, "body": body}) + "\n")

    async def label_pr(self, pr: int, name: str) -> None:
        self.calls.append(("label_pr", pr, name))
        self.label_names.add(name)
        self.labels.setdefault(pr, set()).add(name)

    async def pr_state(self, pr: int) -> tuple[str, str | None, str | None]:
        """The PR's head is what was pushed for it unless `pr_heads` (or `pr_heads` in the
        state file) says otherwise: the owner pushed to it before merging."""
        self.calls.append(("pr_state", pr))
        states = {str(k): v for k, v in self.pr_states.items()}
        heads = {str(k): v for k, v in self.pr_heads.items()}
        if self.state_file is not None and self.state_file.is_file():
            data = json.loads(self.state_file.read_text(encoding="utf-8"))
            states.update({str(k): v for k, v in (data.get("pr_states") or {}).items()})
            heads.update({str(k): v for k, v in (data.get("pr_heads") or {}).items()})
        state = str(states.get(str(pr), "OPEN")).upper()
        merge = _sha(f"owner-merge:{pr}") if state == "MERGED" else None
        return state, merge, heads.get(str(pr), self.head_of(pr))


def _script_ok(book: FixtureBook | None, key: StepKey | None) -> bool:
    """A script entry with a non-zero `exit_code` makes the fake rebase, CI or merge fail."""
    if book is None or key is None:
        return True
    return int(book.lookup(key.scope, key.step, key.round, "script").get("exit_code", 0)) == 0


def fake_ports(book: FixtureBook, root: str, settings: Settings | None = None) -> Ports:
    """`settings` gives the fake gh the pipeline's path tiers, as `real_ports` does."""
    fs = FakeFs()
    git = FakeGit(book, root, fs)
    gh = FakeGh(
        book,
        state_file=Path(root) / "fake-gh.json",
        files_of=lambda head: sorted(git.branch_files.get(head, ())),
        settings=settings,
    )

    def head_of(pr: int) -> str | None:
        prs = dict(gh.prs)
        saved = Path(root) / "fake-gh-prs.json"
        if not prs and saved.is_file():
            prs = json.loads(saved.read_text(encoding="utf-8"))
        branch = next((h for h, n in prs.items() if n == pr), None)
        return git.pushed_head(branch) if branch else None

    gh.head_of = head_of
    return Ports(
        claude=FakeClaude(book, fs),
        git=git,
        gh=gh,
        commands=FakeCommands(book),
        fs=fs,
        clock=FakeClock(),
        notifier=FakeNotifier(),
    )
