"""Rules every `git` adapter enforces (AD-12), and the real subprocess adapter `GitCli`."""

from __future__ import annotations

import asyncio
import fnmatch
import re
import shutil
import time
from pathlib import Path

from sdlc.adapters.commands import branch_point
from sdlc.adapters.proc import ProcResult, run_proc
from sdlc.ports import AdapterError, StagedChange, StepKey

PROTECTED = frozenset({"main", "refs/heads/main", "origin/main"})
PROPOSAL_PREFIX = "proposal/"  # AD-26: orchestrator-owned branches, pushed with --force
# AD-27: another process sharing the repository holds a ref or index lock.
_REF_LOCK = re.compile(r"cannot lock ref|unable to create '[^']*\.lock'|\.lock': File exists",
                       re.IGNORECASE)  # fmt: skip
FETCH_TRIES = 5
STALE_LOCK_S = 60.0  # without `lsof`, a lock this old counts as abandoned


def refuse_main(branch: str) -> None:
    """The orchestrator never pushes to `main`; the guard hook does not cover its own git calls."""
    target = branch.strip().lstrip("+").split(":")[-1].strip().lstrip("+")
    if target in PROTECTED or target.removeprefix("refs/heads/") == "main":
        raise AdapterError("refusing to push to main (AD-12)")


async def _held(path: Path) -> bool:
    """Some process has the file open (`lsof`), or, without `lsof`, it is recent."""
    try:
        res = await run_proc(("lsof", "-t", "--", str(path)), cwd=path.parent, timeout_s=30)
    except AdapterError:  # no lsof
        try:
            return time.time() - path.stat().st_mtime < STALE_LOCK_S
        except OSError:
            return False
    return bool(res.stdout.strip())


def commit_message(run_id: str, scope: str, step: str, round_: int) -> str:
    return f"sdlc({run_id}): {scope}/{step} r{round_}"


class GitCli:
    """The real `git` adapter (AD-6, AD-12, AD-13, AD-20).

    It runs in the main checkout `repo`; worktrees live beside it in
    `<repo-parent>/<repo-name>-worktrees/<branch with "/" replaced by "__">`.
    """

    def __init__(
        self,
        repo: str | Path,
        remote: str = "origin",
        timeout_s: float = 600.0,
        fetch_backoff_s: float = 1.0,
    ) -> None:
        self.repo = Path(repo).resolve()
        self.remote = remote
        self.timeout_s = timeout_s
        self.fetch_backoff_s = fetch_backoff_s
        # Lease values for --force-with-lease: what this adapter last pushed per branch, else the
        # remote SHA seen before the first fetch (a fetch must not refresh the lease).
        self._pushed: dict[str, str] = {}
        self._before_fetch: dict[str, str] = {}
        self._fetched = False

    def worktree_path(self, branch: str) -> Path:
        return self.repo.parent / f"{self.repo.name}-worktrees" / branch.replace("/", "__")

    async def _git(self, cwd: str | Path, *args: str, check: bool = True) -> ProcResult:
        res = await run_proc(("git", *args), cwd=cwd, timeout_s=self.timeout_s)
        if check and not res.ok:
            what = "timed out" if res.timed_out else f"exit {res.returncode}"
            raise AdapterError(f"git {' '.join(args[:3])} {what}: {res.tail(500)}")
        return res

    async def _remote_heads(self) -> dict[str, str]:
        prefix = f"refs/remotes/{self.remote}/"
        out = (
            await self._git(self.repo, "for-each-ref", "--format=%(refname) %(objectname)", prefix)
        ).stdout
        heads: dict[str, str] = {}
        for line in out.splitlines():
            ref, _, sha = line.partition(" ")
            heads[ref.removeprefix(prefix)] = sha
        return heads

    async def fetch(self) -> None:
        if not self._fetched:
            self._before_fetch = await self._remote_heads()
            self._fetched = True
        for attempt in range(FETCH_TRIES):  # AD-27: a concurrent fetch holds the ref lock
            res = await self._git(self.repo, "fetch", "--prune", self.remote, check=False)
            if res.ok:
                return
            if not _REF_LOCK.search(res.stdout + res.stderr) or attempt == FETCH_TRIES - 1:
                what = "timed out" if res.timed_out else f"exit {res.returncode}"
                raise AdapterError(f"git fetch {what}: {res.tail(500)}")
            await asyncio.sleep(self.fetch_backoff_s * 2**attempt)

    async def clear_stale_locks(self, worktree: str) -> list[str]:
        """AD-27: after a crash, `*.lock` files left in the worktree's git dir (`HEAD.lock`,
        `index.lock`, ref locks) make every later git call fail. Remove those no process holds
        (`lsof`; without it, those older than STALE_LOCK_S)."""
        dot_git = Path(worktree) / ".git"
        try:
            text = dot_git.read_text(encoding="utf-8").strip()
        except (OSError, UnicodeDecodeError):
            return []
        if not text.startswith("gitdir:"):
            return []
        gitdir = Path(text.removeprefix("gitdir:").strip())
        if not gitdir.is_absolute():
            gitdir = (Path(worktree) / gitdir).resolve()
        removed: list[str] = []
        for lock in sorted(gitdir.rglob("*.lock")):
            if lock.is_file() and not await _held(lock):
                lock.unlink(missing_ok=True)
                removed.append(str(lock))
        return removed

    async def ensure_worktree(self, branch: str, base: str) -> tuple[str, str]:
        path = self.worktree_path(branch)
        if path.exists():
            current = (
                await self._git(path, "rev-parse", "--abbrev-ref", "HEAD", check=False)
                if (path / ".git").exists()
                else None
            )
            if current is not None and current.ok and current.stdout.strip() == branch:
                return str(path), await self.head(str(path))  # AD-13: reuse it on a resume
            # A stale worktree (pruned gitdir, another branch) or a leftover of a failed add.
            await self._git(self.repo, "worktree", "remove", "--force", str(path), check=False)
            if path.exists():
                shutil.rmtree(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        await self._git(self.repo, "worktree", "prune")
        exists = await self._git(
            self.repo, "rev-parse", "--verify", "--quiet", f"refs/heads/{branch}", check=False
        )
        if exists.ok:
            await self._git(self.repo, "worktree", "add", str(path), branch)
        else:
            await self._git(self.repo, "worktree", "add", "-b", branch, str(path), base)
        return str(path), await self.head(str(path))

    async def head(self, worktree: str) -> str:
        return (await self._git(worktree, "rev-parse", "HEAD")).stdout.strip()

    async def _git_path(self, worktree: str, name: str) -> Path:
        out = (await self._git(worktree, "rev-parse", "--git-path", name)).stdout.strip()
        p = Path(out)
        return p if p.is_absolute() else Path(worktree) / p

    async def abort_rebase(self, worktree: str) -> bool:
        """Abort a rebase in progress; True when there was one."""
        if not Path(worktree).exists():
            return False
        if not await self._rebase_in_progress(worktree):
            return False
        await self._git(worktree, "rebase", "--abort")
        return True

    async def _rebase_in_progress(self, worktree: str) -> bool:
        for name in ("rebase-merge", "rebase-apply"):
            if (await self._git_path(worktree, name)).exists():
                return True
        return False

    async def diff(self, worktree: str, since: str, exclude: tuple[str, ...] = ()) -> str:
        """Changes since `since` (commits and the work tree), untracked files' content included,
        leaving out the tier entries in `exclude` (AD-26: the fix prompt never replays a
        proposal).

        Untracked files are marked intent-to-add first; the caller resets the worktree next.
        """
        untracked = await self._untracked(worktree)
        if untracked:
            await self._git(worktree, "add", "--intent-to-add", "--", *untracked)
        specs = [f":(exclude,literal){e}" for e in exclude]
        args = ["diff", "--binary", since, *(["--", ".", *specs] if specs else [])]
        return (await self._git(worktree, *args)).stdout

    async def reset_hard(self, worktree: str, sha: str) -> None:
        """AD-6: `reset --hard` then `clean -fd`, never `-x`, so ignored `.sdlc/` survives."""
        await self._git(worktree, "reset", "--hard", sha)
        await self._git(worktree, "clean", "-fd")

    async def _untracked(self, worktree: str) -> list[str]:
        out = (await self._git(worktree, "ls-files", "--others", "--exclude-standard", "-z")).stdout
        return [p for p in out.split("\0") if p]

    async def _tracked_changes(self, worktree: str) -> list[str]:
        out = (
            await self._git(worktree, "diff", "--no-renames", "--name-only", "-z", "HEAD")
        ).stdout
        return [p for p in out.split("\0") if p]

    async def restore_paths(self, worktree: str, patterns: tuple[str, ...]) -> None:
        """AD-21: tracked matches go back to HEAD (the scope head); untracked ones are removed."""
        if not patterns:
            return

        def matches(path: str) -> bool:
            return any(fnmatch.fnmatchcase(path, pat) for pat in patterns)

        tracked = [p for p in await self._tracked_changes(worktree) if matches(p)]
        in_head = set()
        if tracked:
            out = (await self._git(worktree, "ls-tree", "-r", "--name-only", "-z", "HEAD")).stdout
            in_head = {p for p in out.split("\0") if p}
        restore = [p for p in tracked if p in in_head]
        added = [p for p in tracked if p not in in_head]  # staged new files
        if restore:
            await self._git(worktree, "checkout", "HEAD", "--", *restore)
        if added:
            await self._git(worktree, "rm", "--cached", "--quiet", "-f", "--", *added)
        for rel in [*added, *(p for p in await self._untracked(worktree) if matches(p))]:
            (Path(worktree) / rel).unlink(missing_ok=True)

    async def commit_all(self, worktree: str, message: str) -> str | None:
        """Commit every change; None when there is nothing to commit."""
        await self._git(worktree, "add", "-A")
        staged = await self._git(worktree, "diff", "--cached", "--quiet", check=False)
        if staged.returncode == 0:
            return None
        # --no-verify: hooks in the worktree are agent-editable code; the orchestrator's own
        # commit must not run them. The gates (tests, reviews) are what verify the change.
        await self._git(worktree, "commit", "--quiet", "--no-verify", "-m", message)
        return await self.head(worktree)

    async def tag(self, name: str, sha: str) -> None:
        """AD-13: reuse a tag already at `sha`; otherwise move it. Local only, never pushed."""
        ref = f"refs/tags/{name}^{{commit}}"
        current = await self._git(self.repo, "rev-parse", "--verify", "--quiet", ref, check=False)
        if current.ok and current.stdout.strip() == sha:
            return
        await self._git(self.repo, "tag", "-f", name, sha)

    async def rebase(self, worktree: str, onto: str, key: StepKey | None = None) -> bool:
        """True when rebased; False on a conflict (a rebase is left in progress). Any other
        failure (a dirty tree, a bad ref, a timeout) raises AdapterError."""
        res = await self._git(worktree, "rebase", onto, check=False)
        if res.ok:
            return True
        if await self._rebase_in_progress(worktree):
            return False
        raise AdapterError(f"git rebase {onto} failed without a conflict: {res.tail(500)}")

    async def changed_paths(self, worktree: str) -> list[str]:
        """Paths changed since the last commit: tracked changes and untracked files."""
        return sorted({*await self._tracked_changes(worktree), *await self._untracked(worktree)})

    async def push(self, worktree: str, branch: str, force: bool = False) -> None:
        refuse_main(branch)  # AD-12: before any git call
        refspec = f"HEAD:refs/heads/{branch}"
        refuse_main(refspec)
        if force:  # AD-26: only proposal branches, which only the orchestrator writes
            if not branch.startswith(PROPOSAL_PREFIX):
                raise AdapterError(f"refusing to force-push {branch}: not a proposal branch")
            sha = await self.head(worktree)
            await self._git(worktree, "push", "--force", self.remote, refspec)
            self._pushed[branch] = sha
            return
        if branch in self._pushed:
            expected = self._pushed[branch]
        elif self._fetched:
            expected = self._before_fetch.get(branch, "")
        else:
            expected = (await self._remote_heads()).get(branch, "")
        # An explicit lease: the remote must still be where we last saw it ("" = must not exist).
        lease = f"--force-with-lease=refs/heads/{branch}:{expected}"
        sha = await self.head(worktree)
        await self._git(worktree, "push", lease, self.remote, refspec)
        self._pushed[branch] = sha

    async def show(self, ref: str, path: str) -> str | None:
        res = await self._git(self.repo, "show", f"{ref}:{path}", check=False)
        return res.stdout if res.ok else None

    async def staged_diff(self, worktree: str, paths: tuple[str, ...]) -> StagedChange:
        """AD-26: `git add -A -- <paths>` (new files included), then
        `git diff --cached --binary --full-index HEAD -- <paths>`, with each path's staged
        mode and blob (None when deleted)."""
        if not paths:
            return StagedChange(patch="")
        await self._git(worktree, "--literal-pathspecs", "add", "-A", "--", *paths)
        res = await self._git(
            worktree, "--literal-pathspecs", "diff", "--cached", "--binary", "--full-index",
            "HEAD", "--", *paths,
        )  # fmt: skip
        staged = (
            await self._git(worktree, "--literal-pathspecs", "ls-files", "-s", "-z", "--", *paths)
        ).stdout
        blobs: dict[str, str] = {}
        for entry in staged.split("\0"):
            meta, _, path = entry.partition("\t")
            if path:
                mode, blob, _stage = meta.split(" ")
                blobs[path] = f"{mode} {blob}"
        entries = tuple((p, blobs.get(p)) for p in sorted(set(paths)))
        return StagedChange(patch=res.stdout, entries=entries)

    async def branch_point(self, worktree: str) -> str | None:
        return await branch_point(worktree)

    async def remove_worktree(self, worktree: str, branch: str, remote: bool = False) -> None:
        """AD-26: proposal worktrees and local branches do not pile up. Only proposal branches."""
        if not branch.startswith(PROPOSAL_PREFIX):
            raise AdapterError(f"refusing to remove {branch}: not a proposal branch")
        refuse_main(branch)
        await self._git(self.repo, "worktree", "remove", "--force", worktree, check=False)
        if Path(worktree).exists():
            shutil.rmtree(worktree)
        await self._git(self.repo, "worktree", "prune", check=False)
        await self._git(self.repo, "branch", "-D", branch, check=False)
        if remote:
            await self._git(
                self.repo, "push", self.remote, "--delete", f"refs/heads/{branch}", check=False
            )
            self._pushed.pop(branch, None)

    async def branch_paths(self, worktree: str) -> list[str]:
        """AD-27: the paths the branch changes since its merge base with origin/main."""
        base = f"{self.remote}/main...HEAD"
        out = (await self._git(worktree, "diff", "--no-renames", "--name-only", "-z", base)).stdout
        return sorted({p for p in out.split("\0") if p})

    async def apply_3way(self, worktree: str, patch: str) -> bool:
        """AD-26: applied only when `git apply --3way` exits 0 and no unmerged entry is left."""
        res = await run_proc(
            ("git", "apply", "--3way", "--whitespace=nowarn", "-"),
            cwd=worktree,
            stdin=patch,
            timeout_s=self.timeout_s,
        )
        unmerged = (await self._git(worktree, "ls-files", "-u")).stdout.strip()
        return res.ok and not unmerged
