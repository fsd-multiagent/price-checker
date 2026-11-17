"""Rules every `git` adapter enforces (AD-12), and the real subprocess adapter `GitCli`."""

from __future__ import annotations

import fnmatch
import shutil
from pathlib import Path

from sdlc.adapters.proc import ProcResult, run_proc
from sdlc.ports import AdapterError, StepKey

PROTECTED = frozenset({"main", "refs/heads/main", "origin/main"})


def refuse_main(branch: str) -> None:
    """The orchestrator never pushes to `main`; the guard hook does not cover its own git calls."""
    target = branch.strip().lstrip("+").split(":")[-1].strip().lstrip("+")
    if target in PROTECTED or target.removeprefix("refs/heads/") == "main":
        raise AdapterError("refusing to push to main (AD-12)")


def commit_message(run_id: str, scope: str, step: str, round_: int) -> str:
    return f"sdlc({run_id}): {scope}/{step} r{round_}"


class GitCli:
    """The real `git` adapter (AD-6, AD-12, AD-13, AD-20).

    It runs in the main checkout `repo`; worktrees live beside it in
    `<repo-parent>/<repo-name>-worktrees/<branch with "/" replaced by "__">`.
    """

    def __init__(self, repo: str | Path, remote: str = "origin", timeout_s: float = 600.0) -> None:
        self.repo = Path(repo).resolve()
        self.remote = remote
        self.timeout_s = timeout_s
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
        await self._git(self.repo, "fetch", "--prune", self.remote)

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

    async def diff(self, worktree: str, since: str) -> str:
        """Changes since `since` (commits and the work tree), untracked files' content included.

        Untracked files are marked intent-to-add first; the caller resets the worktree next.
        """
        untracked = await self._untracked(worktree)
        if untracked:
            await self._git(worktree, "add", "--intent-to-add", "--", *untracked)
        return (await self._git(worktree, "diff", "--binary", since)).stdout

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

    async def push(self, worktree: str, branch: str) -> None:
        refuse_main(branch)  # AD-12: before any git call
        refspec = f"HEAD:refs/heads/{branch}"
        refuse_main(refspec)
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
