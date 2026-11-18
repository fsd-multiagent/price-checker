"""Real adapters (spec-orchestrator-real-adapters): one test per I/O matrix row, plus AC-1.

No paid `claude` call and no GitHub call: `claude` and `gh` are stub executables on a temporary
PATH, and `git` is real, in temporary repos with a bare `origin`.
"""

from __future__ import annotations

import asyncio
import json
import os
import shlex
import shutil
import subprocess
import sys
import time
import types
from pathlib import Path

import pytest
from conftest import ORCH

from sdlc.adapters import gh as gh_mod
from sdlc.adapters import real
from sdlc.adapters.claude import ClaudeCli, build_argv
from sdlc.adapters.commands import ProjectCommands, parse_commands
from sdlc.adapters.fake import FakeGit, FixtureBook
from sdlc.adapters.gh import GhCli
from sdlc.adapters.git import GitCli
from sdlc.adapters.notifier import DesktopNotifier, osascript_argv
from sdlc.adapters.proc import run_proc
from sdlc.adapters.real import CREDENTIAL_VARS, credential_problems
from sdlc.cli import main
from sdlc.core.fr_ac import check_prds, check_text
from sdlc.ports import AdapterError, Invocation, StepKey

STUBS = ORCH / "tests" / "stubs"
KEY = StepKey(seq=1, scope="story:1-1-a", stage="build", step="dev", round=1)


# --------------------------------------------------------------------------- fixtures


def _install(bin_dir: Path, name: str, stub: str) -> None:
    exe = bin_dir / name
    exe.write_text(f"#!{sys.executable}\n" + (STUBS / stub).read_text(encoding="utf-8"))
    exe.chmod(0o755)


@pytest.fixture
def stubs(tmp_path, monkeypatch):
    """`claude` and `gh` stubs first on PATH; returns (bin dir, call log)."""
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _install(bin_dir, "claude", "claude_stub.py")
    _install(bin_dir, "gh", "gh_stub.py")
    log = tmp_path / "calls.jsonl"
    monkeypatch.setenv("PATH", f"{bin_dir}{os.pathsep}{os.environ['PATH']}")
    monkeypatch.setenv("STUB_LOG", str(log))
    return bin_dir, log


def calls(log: Path, tool: str) -> list[dict]:
    if not log.exists():
        return []
    rows = [json.loads(x) for x in log.read_text(encoding="utf-8").splitlines() if x.strip()]
    return [r for r in rows if r["tool"] == tool]


def gh_config(tmp_path: Path, monkeypatch, **cfg) -> None:
    path = tmp_path / "gh-config.json"
    path.write_text(json.dumps(cfg), encoding="utf-8")
    monkeypatch.setenv("STUB_GH_CONFIG", str(path))


def git(cwd: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=cwd, check=True, capture_output=True, text=True
    ).stdout.strip()


@pytest.fixture
def repo(tmp_path, monkeypatch) -> Path:
    """A main checkout `work/` cloned from a bare `origin.git`, with one commit on main."""
    empty = tmp_path / "gitconfig"
    empty.write_text("", encoding="utf-8")
    monkeypatch.setenv("GIT_CONFIG_GLOBAL", str(empty))
    monkeypatch.setenv("GIT_CONFIG_NOSYSTEM", "1")
    for k, v in {"NAME": "Test", "EMAIL": "test@example.invalid"}.items():
        monkeypatch.setenv(f"GIT_AUTHOR_{k}", v)
        monkeypatch.setenv(f"GIT_COMMITTER_{k}", v)
    origin = tmp_path / "origin.git"
    git(tmp_path, "init", "--quiet", "--bare", "-b", "main", str(origin))
    work = tmp_path / "work"
    git(tmp_path, "clone", "--quiet", str(origin), str(work))
    git(work, "checkout", "--quiet", "-B", "main")
    git(work, "config", "user.email", "test@example.invalid")
    git(work, "config", "user.name", "Test")
    (work / ".gitignore").write_text(".sdlc/\n_bmad-output/sdlc/\n", encoding="utf-8")
    (work / "a.txt").write_text("one\n", encoding="utf-8")
    (work / "AGENTS.md").write_text("# Agents\n", encoding="utf-8")
    git(work, "add", "-A")
    git(work, "commit", "--quiet", "-m", "init")
    git(work, "push", "--quiet", "origin", "HEAD:refs/heads/main")
    git(work, "fetch", "--quiet", "origin")
    return work


def commit_file(cwd: Path, rel: str, text: str, msg: str) -> str:
    (cwd / rel).parent.mkdir(parents=True, exist_ok=True)
    (cwd / rel).write_text(text, encoding="utf-8")
    git(cwd, "add", "-A")
    git(cwd, "commit", "--quiet", "-m", msg)
    return git(cwd, "rev-parse", "HEAD")


# --------------------------------------------------------------------------- claude


def _inv(cwd: Path, env: dict[str, str], timeout_s: float = 30.0) -> Invocation:
    argv = build_argv(budget_usd=1, allowed_tools=("Read",), restrict_tools=True)
    return Invocation(key=KEY, argv=argv, stdin="the prompt", cwd=str(cwd), env=env,
                      timeout_s=timeout_s)  # fmt: skip


async def test_real_claude_happy(stubs, tmp_path):
    _, log = stubs
    wt = tmp_path / "wt"
    wt.mkdir()
    result = await ClaudeCli().run(_inv(wt, {"SDLC_VERDICT_PATH": "/v.json", "TMPDIR": "/t"}))
    assert result.subtype == "success" and not result.is_error
    assert result.total_cost_usd == 0.01 and result.num_turns == 2
    (call,) = calls(log, "claude")
    assert Path(call["cwd"]).resolve() == wt.resolve()
    assert call["stdin"] == "the prompt"
    assert call["env"]["SDLC_VERDICT_PATH"] == "/v.json" and call["env"]["TMPDIR"] == "/t"
    assert str(stubs[0]) in call["env"]["PATH"]  # the parent environment is kept
    assert call["argv"][:3] == ["-p", "--output-format", "json"]


def _alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    return True


async def test_real_claude_timeout(stubs, tmp_path):
    pid_file = tmp_path / "child.pid"
    env = {"STUB_CLAUDE_MODE": "timeout", "STUB_CHILD_PID": str(pid_file)}
    started = time.monotonic()
    result = await ClaudeCli().run(_inv(tmp_path, env, timeout_s=1.5))
    assert time.monotonic() - started < 15
    assert result.subtype == "error_timeout" and result.is_error
    assert result.total_cost_usd == 1.0  # charged the step's cap, not $0
    child = int(pid_file.read_text(encoding="utf-8"))
    deadline = time.monotonic() + 5
    while _alive(child) and time.monotonic() < deadline:
        time.sleep(0.05)
    assert not _alive(child), "the stub's child outlived the timeout (no orphan allowed)"


async def test_real_claude_garbage(stubs, tmp_path):
    with pytest.raises(AdapterError, match="exited 1"):
        await ClaudeCli().run(_inv(tmp_path, {"STUB_CLAUDE_MODE": "garbage"}))


async def test_real_proc_cancellation_kills_the_group(tmp_path):
    pid_file = tmp_path / "child.pid"
    code = (
        "import subprocess, sys, time, pathlib\n"
        "c = subprocess.Popen([sys.executable, '-c', 'import time\\ntime.sleep(300)'])\n"
        f"pathlib.Path({str(pid_file)!r}).write_text(str(c.pid))\ntime.sleep(300)\n"
    )
    task = asyncio.ensure_future(run_proc([sys.executable, "-c", code], timeout_s=60))
    deadline = time.monotonic() + 10
    while not pid_file.exists() and time.monotonic() < deadline:
        await asyncio.sleep(0.05)
    child = int(pid_file.read_text(encoding="utf-8"))
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    deadline = time.monotonic() + 5
    while _alive(child) and time.monotonic() < deadline:
        await asyncio.sleep(0.05)
    assert not _alive(child)


# --------------------------------------------------------------------------- git


async def test_real_worktree_lifecycle(repo):
    g = GitCli(repo)
    await g.fetch()
    path, head = await g.ensure_worktree("story/1-1-a", "origin/main")
    expected = repo.parent / "work-worktrees" / "story__1-1-a"
    assert Path(path) == expected.resolve() and expected.is_dir()
    assert head == git(repo, "rev-parse", "origin/main")
    assert git(expected, "rev-parse", "--abbrev-ref", "HEAD") == "story/1-1-a"
    again = await g.ensure_worktree("story/1-1-a", "origin/main")
    assert again == (path, head)
    assert await g.head(path) == head


async def test_real_worktree_recreated_and_stale(repo):
    g = GitCli(repo)
    path, _ = await g.ensure_worktree("story/1-1-a", "origin/main")
    work = commit_file(Path(path), "b.txt", "story\n", "story work")
    shutil.rmtree(path)  # deleted, not pruned: re-created on the existing branch
    again, head = await g.ensure_worktree("story/1-1-a", "origin/main")
    assert again == path and head == work and (Path(path) / "b.txt").exists()
    git(Path(path), "checkout", "--quiet", "-b", "other")  # a worktree on another branch
    _, head = await g.ensure_worktree("story/1-1-a", "origin/main")
    assert git(Path(path), "rev-parse", "--abbrev-ref", "HEAD") == "story/1-1-a" and head == work
    leftover = g.worktree_path("story/1-2-b")  # a failed add left a dir without .git
    leftover.mkdir(parents=True)
    (leftover / "junk").write_text("x", encoding="utf-8")
    p2, _ = await g.ensure_worktree("story/1-2-b", "origin/main")
    assert (Path(p2) / ".git").exists() and not (Path(p2) / "junk").exists()


async def test_real_worktree_for_existing_branch(repo):
    git(repo, "branch", "story/1-3-c", "HEAD")
    sha = git(repo, "rev-parse", "story/1-3-c")
    path, head = await GitCli(repo).ensure_worktree("story/1-3-c", "origin/main")
    assert head == sha and git(Path(path), "rev-parse", "--abbrev-ref", "HEAD") == "story/1-3-c"


async def test_real_reset_keeps_sdlc(repo):
    g = GitCli(repo)
    path, head = await g.ensure_worktree("story/1-1-a", "origin/main")
    wt = Path(path)
    (wt / "a.txt").write_text("changed\n", encoding="utf-8")
    (wt / "new.txt").write_text("untracked\n", encoding="utf-8")
    (wt / "dir").mkdir()
    (wt / "dir" / "x.txt").write_text("untracked\n", encoding="utf-8")
    (wt / ".sdlc").mkdir()
    (wt / ".sdlc" / "x").write_text("keep\n", encoding="utf-8")
    assert sorted(await g.changed_paths(path)) == ["a.txt", "dir/x.txt", "new.txt"]
    patch = await g.diff(path, head)
    assert "changed" in patch and "+untracked" in patch  # untracked content, not just names
    await g.reset_hard(path, head)
    assert (wt / "a.txt").read_text(encoding="utf-8") == "one\n"
    assert not (wt / "new.txt").exists() and not (wt / "dir").exists()
    assert (wt / ".sdlc" / "x").read_text(encoding="utf-8") == "keep\n"
    assert await g.changed_paths(path) == []


async def test_real_restore_paths_and_commit(repo):
    g = GitCli(repo)
    commit_file(repo, "docs/source-register.md", "reg\n", "register")
    git(repo, "push", "--quiet", "origin", "HEAD:refs/heads/main")
    await g.fetch()
    path, head = await g.ensure_worktree("story/1-1-a", "origin/main")
    wt = Path(path)
    (wt / "AGENTS.md").write_text("edited\n", encoding="utf-8")
    (wt / "docs" / "source-register.md").write_text("edited\n", encoding="utf-8")
    (wt / "docs" / "new-register.md").write_text("new\n", encoding="utf-8")
    (wt / "code.py").write_text("x = 1\n", encoding="utf-8")
    await g.restore_paths(path, ("AGENTS.md", "docs/*-register.md"))
    assert (wt / "AGENTS.md").read_text(encoding="utf-8") == "# Agents\n"
    assert (wt / "docs" / "source-register.md").read_text(encoding="utf-8") == "reg\n"
    assert not (wt / "docs" / "new-register.md").exists()
    sha = await g.commit_all(path, "sdlc(r1): story:1-1-a/dev r1")
    assert sha and sha != head
    assert git(wt, "show", "--name-only", "--format=", "HEAD") == "code.py"
    assert await g.commit_all(path, "nothing") is None
    (wt / "docs" / "staged-register.md").write_text("new\n", encoding="utf-8")  # staged new
    git(wt, "add", "docs/staged-register.md")
    git(wt, "mv", "docs/source-register.md", "docs/moved.md")  # a rename of a protected file
    await g.restore_paths(path, ("docs/*-register.md",))
    await g.commit_all(path, "after restore")
    names = git(wt, "show", "--name-status", "--format=", "HEAD")
    assert "staged-register.md" not in names and "source-register.md" not in names
    assert (wt / "docs" / "source-register.md").read_text(encoding="utf-8") == "reg\n"


async def test_real_rebase_conflict(repo):
    g = GitCli(repo)
    path, _ = await g.ensure_worktree("story/1-1-a", "origin/main")
    commit_file(Path(path), "a.txt", "story side\n", "story")
    commit_file(repo, "a.txt", "main side\n", "main")
    git(repo, "push", "--quiet", "origin", "HEAD:refs/heads/main")
    await g.fetch()
    assert await g.rebase(path, "origin/main") is False
    assert await g.abort_rebase(path) is True
    assert git(Path(path), "status", "--porcelain") == ""
    assert await g.abort_rebase(path) is False


async def test_real_rebase_clean(repo):
    g = GitCli(repo)
    path, _ = await g.ensure_worktree("story/1-1-a", "origin/main")
    commit_file(Path(path), "b.txt", "story\n", "story")
    commit_file(repo, "c.txt", "main\n", "main")
    git(repo, "push", "--quiet", "origin", "HEAD:refs/heads/main")
    await g.fetch()
    assert await g.rebase(path, "origin/main") is True
    assert (Path(path) / "c.txt").exists()
    with pytest.raises(AdapterError, match="without a conflict"):
        await g.rebase(path, "origin/no-such-branch")


@pytest.mark.parametrize("branch", ["main", "+main"])
@pytest.mark.parametrize("adapter", ["real", "fake"])
async def test_real_push_refusal(tmp_path, branch, adapter):
    """Same contract for the real and the fake adapter; the real one makes no git call."""
    missing = tmp_path / "no-such-repo"
    if adapter == "real":
        g = GitCli(missing)
    else:
        g = FakeGit(FixtureBook.from_scenario(ORCH / "tests" / "fixtures" / "dryrun", None), "x")
    with pytest.raises(AdapterError, match="refusing to push to main"):
        await g.push(str(missing), branch)


async def test_real_push_story_branch_with_lease(repo):
    g = GitCli(repo)
    path, _ = await g.ensure_worktree("story/1-1-a", "origin/main")
    first = commit_file(Path(path), "b.txt", "one\n", "story")
    await g.push(path, "story/1-1-a")
    assert git(repo, "ls-remote", "origin", "refs/heads/story/1-1-a").split()[0] == first
    git(Path(path), "commit", "--quiet", "--amend", "-m", "amended")  # a rewritten branch
    await g.push(path, "story/1-1-a")
    amended = git(Path(path), "rev-parse", "HEAD")
    assert git(repo, "ls-remote", "origin", "refs/heads/story/1-1-a").split()[0] == amended


async def test_real_push_keeps_a_foreign_remote_commit(repo, tmp_path):
    g = GitCli(repo)
    await g.fetch()
    path, _ = await g.ensure_worktree("story/1-1-a", "origin/main")
    commit_file(Path(path), "b.txt", "one\n", "story")
    await g.push(path, "story/1-1-a")
    other = tmp_path / "other"  # someone else pushes to the story branch
    git(tmp_path, "clone", "--quiet", "-b", "story/1-1-a", str(tmp_path / "origin.git"), str(other))
    foreign = commit_file(other, "f.txt", "foreign\n", "foreign")
    git(other, "push", "--quiet", "origin", "HEAD:refs/heads/story/1-1-a")
    await g.fetch()  # the fetch must not refresh the lease
    git(Path(path), "commit", "--quiet", "--amend", "-m", "rewritten")
    with pytest.raises(AdapterError):
        await g.push(path, "story/1-1-a")
    assert git(repo, "ls-remote", "origin", "refs/heads/story/1-1-a").split()[0] == foreign


async def test_real_tag_repass(repo):
    g = GitCli(repo)
    old = git(repo, "rev-parse", "HEAD")
    await g.tag("sdlc/r1/analysis", old)
    await g.tag("sdlc/r1/analysis", old)  # same SHA: reused
    new = commit_file(repo, "d.txt", "d\n", "more")
    await g.tag("sdlc/r1/analysis", new)
    assert git(repo, "rev-parse", "sdlc/r1/analysis^{commit}") == new
    assert git(repo, "ls-remote", "--tags", "origin") == ""  # local only


async def test_real_show_reads_origin_main(repo):
    g = GitCli(repo)
    assert await g.show("origin/main", "a.txt") == "one\n"
    assert await g.show("origin/main", "missing.md") is None


# --------------------------------------------------------------------------- gh


async def test_real_pr_reuse(stubs, tmp_path, monkeypatch):
    _, log = stubs
    gh_config(tmp_path, monkeypatch, pr_list=[{"number": 17}])
    assert await GhCli(tmp_path).ensure_pr("story/1-1-a", "main", "t", "b") == 17
    assert not [c for c in calls(log, "gh") if c["argv"][:2] == ["pr", "create"]]
    gh_config(tmp_path, monkeypatch, pr_list=[])
    assert await GhCli(tmp_path).ensure_pr("story/1-1-a", "main", "t", "b") == 42
    create = [c for c in calls(log, "gh") if c["argv"][:2] == ["pr", "create"]]
    assert len(create) == 1 and "--head" in create[0]["argv"]
    gh_config(tmp_path, monkeypatch, pr_list=[{"url": "x"}])
    with pytest.raises(AdapterError, match="without a number"):
        await GhCli(tmp_path).ensure_pr("story/1-1-a", "main", "t", "b")


def _gh(log: Path, *prefix: str) -> list[list[str]]:
    return [c["argv"] for c in calls(log, "gh") if c["argv"][: len(prefix)] == list(prefix)]


PASS = {"name": "ci", "state": "SUCCESS", "bucket": "pass"}
PENDING = {"name": "ci", "state": "PENDING", "bucket": "pending"}


async def test_real_no_required_checks(stubs, tmp_path, monkeypatch):
    """Zero required checks never counts as green; it polls until the deadline."""
    gh_config(tmp_path, monkeypatch, checks=[], checks_exit=1)
    # A fake clock that ticks 1 s per read: the 3 s deadline allows exactly three polls, however
    # slow the stub `gh` is on a loaded runner (a wall-clock deadline made this test flaky).
    ticks = iter(range(1000))
    monkeypatch.setattr(gh_mod, "time", types.SimpleNamespace(monotonic=lambda: next(ticks)))
    gh = GhCli(tmp_path, poll_s=0.01)
    assert await gh.checks_green(5, 0.05) is False
    assert len(_gh(stubs[1], "pr", "checks")) == 3
    argv = _gh(stubs[1], "pr", "checks")[0]
    assert argv[:3] == ["pr", "checks", "5"] and "--required" in argv and "--json" in argv
    for checks in (["not-a-dict"], [{"name": "ci", "bucket": "skipping"}]):
        gh_config(tmp_path, monkeypatch, checks=checks)
        assert await gh.checks_green(5, 0.001) is False, checks
    gh_config(tmp_path, monkeypatch, checks=[{"name": "ci", "bucket": "fail"}], checks_exit=1)
    assert await gh.checks_green(5, 1) is False


async def test_real_checks_pending_then_pass(stubs, tmp_path, monkeypatch):
    gh_config(tmp_path, monkeypatch, checks_seq=[None, "error", [PENDING], [PASS]])
    assert await GhCli(tmp_path, poll_s=0.01).checks_green(5, 1) is True
    assert len(_gh(stubs[1], "pr", "checks")) == 4


async def test_real_checks_pending_then_timeout(stubs, tmp_path, monkeypatch):
    gh_config(tmp_path, monkeypatch, checks_seq=[[PENDING]])
    assert await GhCli(tmp_path, poll_s=0.01).checks_green(5, 0.001) is False


async def test_real_merge(stubs, tmp_path, monkeypatch):
    _, log = stubs
    merged = {"state": "MERGED", "mergeCommit": {"oid": "abc123"}}
    gh_config(tmp_path, monkeypatch, view=[{"state": "OPEN"}, "error", merged])
    assert await GhCli(tmp_path, poll_s=0.01).merge(9, "deadbeef", paths=[]) == "abc123"
    (merge,) = _gh(log, "pr", "merge")
    assert merge == ["pr", "merge", "9", "--auto", "--squash", "--match-head-commit", "deadbeef"]
    assert "--admin" not in merge
    assert len(_gh(log, "pr", "view")) == 3  # a failed poll is tolerated


async def test_real_merge_already_merged_on_resume(stubs, tmp_path, monkeypatch):
    gh_config(tmp_path, monkeypatch, view=[{"state": "MERGED", "mergeCommit": {"oid": "abc"}}])
    assert await GhCli(tmp_path, poll_s=0.01).merge(9, "deadbeef", paths=[]) == "abc"
    assert _gh(stubs[1], "pr", "merge") == []


async def test_real_merge_timeout_disables_auto(stubs, tmp_path, monkeypatch):
    gh_config(tmp_path, monkeypatch, view=[{"state": "OPEN"}])
    slow = GhCli(tmp_path, poll_s=0.01, merge_timeout_min=0.002)
    assert await slow.merge(9, "deadbeef", paths=[]) is None
    assert _gh(stubs[1], "pr", "merge")[-1] == ["pr", "merge", "9", "--disable-auto"]
    gh_config(tmp_path, monkeypatch, view=[{"state": "CLOSED"}])
    assert await GhCli(tmp_path, poll_s=0.01).merge(9, "deadbeef", paths=[]) is None


async def test_real_merge_without_sha(stubs, tmp_path, monkeypatch):
    gh_config(tmp_path, monkeypatch, view=[{"state": "OPEN"}, {"state": "MERGED"}])
    with pytest.raises(AdapterError, match="merged without SHA"):
        await GhCli(tmp_path, poll_s=0.01).merge(9, "deadbeef", paths=[])


async def test_real_escalation_reuse(stubs, tmp_path, monkeypatch):
    _, log = stubs
    marker = "<!-- sdlc:r1:story:1-1-a -->"
    gh_config(tmp_path, monkeypatch, issues=[{"number": 5, "body": f"{marker}\nold"}])
    gh = GhCli(tmp_path)
    assert await gh.ensure_escalation_issue("r1", "story:1-1-a", "t", "again") == 5
    comments = [c["argv"] for c in calls(log, "gh") if c["argv"][:2] == ["issue", "comment"]]
    assert comments == [["issue", "comment", "5", "--body", "again"]]
    assert not [c for c in calls(log, "gh") if c["argv"][:2] == ["issue", "create"]]
    (listing,) = [c["argv"] for c in calls(log, "gh") if c["argv"][:2] == ["issue", "list"]]
    assert listing[2:4] == ["--label", "escalation"] and "--state" in listing
    # a different scope's marker does not match: a new issue carries its own marker
    assert await gh.ensure_escalation_issue("r1", "story:1-2-b", "t", "body") == 7
    (create,) = [c["argv"] for c in calls(log, "gh") if c["argv"][:2] == ["issue", "create"]]
    assert create[create.index("--body") + 1].startswith("<!-- sdlc:r1:story:1-2-b -->\n")
    order = [c["argv"][:2] for c in calls(log, "gh")]
    assert order.count(["label", "create"]) == 1
    assert order.index(["label", "create"]) < order.index(["issue", "create"])


async def test_real_escalation_reopens_a_closed_issue(stubs, tmp_path, monkeypatch):
    _, log = stubs
    marker = "<!-- sdlc:r1:run -->"
    gh_config(tmp_path, monkeypatch, issues=[{"number": 5, "body": marker, "state": "CLOSED"}])
    assert await GhCli(tmp_path).ensure_escalation_issue("r1", "run", "t", "again") == 5
    order = [c["argv"][:3] for c in calls(log, "gh") if c["argv"][0] == "issue"]
    assert order[1:] == [["issue", "reopen", "5"], ["issue", "comment", "5"]]


# --------------------------------------------------------------------------- commands


def _agents(block: str) -> str:
    return f"# Agents\n\n## Running and verifying\n\n{block}\n## Conventions\n\nnone\n"


def publish(repo: Path, agents: str) -> None:
    """Commit AGENTS.md on main and push it, so origin/main carries the same block."""
    (repo / "AGENTS.md").write_text(agents, encoding="utf-8")
    git(repo, "commit", "--quiet", "-am", "agents")
    git(repo, "push", "--quiet", "origin", "HEAD:refs/heads/main")
    git(repo, "fetch", "--quiet", "origin")


def _block(**cmds: list[str]) -> str:
    body = "".join(f"{n}:\n" + "".join(f"  - {c}\n" for c in cs) for n, cs in cmds.items())
    return _agents(f"```yaml sdlc-commands\n{body}```\n")


def py(code: str) -> str:
    return shlex.join([sys.executable, "-c", code])


async def test_real_commands_missing(repo):
    cmds = ProjectCommands()
    (repo / "AGENTS.md").unlink()
    code, out = await cmds.run("tests", str(repo), 30, KEY)
    assert code == 1 and "no tests commands in AGENTS.md" in out
    publish(repo, _agents("No block here.\n"))
    code, out = await cmds.run("tests", str(repo), 30, KEY)
    assert code == 1 and "no tests commands in AGENTS.md" in out
    publish(repo, _block(tests=[py("print('green')")]))
    code, out = await cmds.run("terraform_plan", str(repo), 30, KEY)
    assert code == 1 and "no terraform_plan commands in AGENTS.md" in out
    code, out = await cmds.run("tests", str(repo), 30, KEY)
    assert code == 0 and "green" in out


async def test_real_commands_differ_from_origin_main(repo):
    publish(repo, _block(tests=[py("print('green')")]))
    (repo / "AGENTS.md").write_text(_block(tests=[py("print('edited')")]), encoding="utf-8")
    code, out = await ProjectCommands().run("tests", str(repo), 30, KEY)
    assert code == 1 and "this branch changed the gate block" in out


async def test_real_commands_main_changed_block_after_branch_point(repo):
    """Regression (run 20261117-174705-full): the owner changed the block on main after a
    story branched. The story did not touch it, so its gate passes and runs the commands from
    its branch point; a change the story itself makes still fails."""
    publish(repo, _block(tests=[py("print('branch-point')")]))
    git(repo, "checkout", "--quiet", "-b", "story")
    git(repo, "checkout", "--quiet", "main")
    publish(repo, _block(tests=[py("print('newer-main')")]))
    git(repo, "checkout", "--quiet", "story")
    code, out = await ProjectCommands().run("tests", str(repo), 30, KEY)
    assert code == 0 and "branch-point" in out and "newer-main" not in out
    (repo / "AGENTS.md").write_text(_block(tests=[py("print('edited')")]), encoding="utf-8")
    code, out = await ProjectCommands().run("tests", str(repo), 30, KEY)
    assert code == 1 and "this branch changed the gate block" in out


async def test_real_commands_fail_and_refuse_shell(repo):
    unsafe = ["cd x && make", "make 2>&1", "a;b", "x|y", "echo >out", '"\'unterminated"']
    publish(repo, _block(tests=[py("raise SystemExit(3)")], lint=unsafe[:1]))
    assert (await ProjectCommands().run("tests", str(repo), 30, KEY))[0] == 3
    for cmd in unsafe:
        publish(repo, _block(lint=[cmd]))
        code, out = await ProjectCommands().run("lint", str(repo), 30, KEY)
        assert code == 1 and ("without a shell" in out or "cannot be split" in out), cmd


@pytest.mark.parametrize(
    "cmd",
    ["terraform apply", "uv run terraform destroy -auto-approve", "git push origin x",
     "gh pr merge 3", "ssh host", "scp a host:b", "rsync -a a host:b", "rsync -e ssh a b"],
)  # fmt: skip
async def test_real_commands_refuse_guard_denials(repo, cmd):
    publish(repo, _block(ops=[cmd]))
    code, out = await ProjectCommands().run("ops", str(repo), 30, KEY)
    assert code == 1 and "denied by the guard rules" in out


async def test_real_commands_missing_executable_timeout_and_worktree(repo, tmp_path):
    slow = py("__import__('time').sleep(30)")
    publish(repo, _block(missing=["no-such-program-xyz"], slow=[slow, slow]))
    assert (await ProjectCommands().run("missing", str(repo), 30, KEY))[0] == 127
    started = time.monotonic()
    code, out = await ProjectCommands().run("slow", str(repo), 1.0, KEY)
    assert code == 124 and time.monotonic() - started < 10  # one deadline for both commands
    code, out = await ProjectCommands().run("slow", str(tmp_path / "gone"), 30, KEY)
    assert code == 1 and "does not exist" in out


async def test_real_commands_bad_agents_md(repo):
    (repo / "AGENTS.md").write_bytes(b"\xff\xfe not utf-8")
    code, out = await ProjectCommands().run("tests", str(repo), 30, KEY)
    assert code == 1 and "not UTF-8" in out


def test_real_commands_section_skips_fenced_headings():
    text = _agents(
        "```sh\n# a comment, not a heading\n```\n\n```yaml sdlc-commands\ntests:\n  - a b\n```\n"
    )
    assert parse_commands(text) == {"tests": ("a b",)}


def test_real_commands_block_in_this_repo():
    """The repo's own AGENTS.md carries the block, with the orchestrator tests (T-8)."""
    text = (ORCH.parent / "AGENTS.md").read_text(encoding="utf-8")
    assert parse_commands(text)["tests"]


def test_real_fr_ac():
    prd = "# PRD\n\n## FR-1 Search\n\nAC-1.1 given an EAN...\n\n## FR-2 Compare\n\nNo criteria.\n"
    res = check_text(prd)
    assert res.defined == ("FR-1", "FR-2") and res.missing == ("FR-2",)
    code, out = check_prds({"prds/prd.md": prd})
    assert code == 1 and "FR-2" in out and "FR-1," not in out
    table = "| ID | Text |\n|---|---|\n| FR-1 | x AC-1 |\n| **FR-2** | y; AC-2.1 |\n"
    assert check_prds({"p.md": table})[0] == 0
    assert check_prds({})[0] == 1
    assert check_prds({"p.md": "see FR-1 later"})[0] == 1  # a reference is no definition


def test_real_fr_ac_dotted_ids_prefixes_and_last_span():
    dotted = "- FR-1: a AC-1\n- FR-1.2: b\n"
    assert check_text(dotted) == (("FR-1", "FR-1.2"), ("FR-1.2",))
    marks = "- [ ] FR-1: a\n  AC-1.1\n> FR-2: b\n> AC-2.1\n- [x] **FR-3**: c AC-3\n"
    assert check_text(marks) == (("FR-1", "FR-2", "FR-3"), ())
    # the last FR's span ends at the next heading of its level: a later AC does not count
    last = "## Requirements\n\n- FR-1: a\n\n## Acceptance\n\nAC-1.1\n"
    assert check_text(last).missing == ("FR-1",)
    own = "## FR-1 a\n\n### Criteria\n\nAC-1.1\n\n## Other\n"
    assert check_text(own).missing == ()


async def test_real_fr_ac_through_commands(tmp_path):
    prds = tmp_path / "_bmad-output" / "planning-artifacts" / "prds"
    prds.mkdir(parents=True)
    (prds / "prd.md").write_text("- FR-1: a\n  - AC-1.1: b\n- FR-2: c\n", encoding="utf-8")
    code, out = await ProjectCommands().run("fr_ac_check", str(tmp_path), 30, KEY)
    assert code == 1 and "FR-2" in out
    (prds / "bad.md").write_bytes(b"\xff\xfe")
    code, out = await ProjectCommands().run("fr_ac_check", str(tmp_path), 30, KEY)
    assert code == 1 and "cannot read" in out


# --------------------------------------------------------------------------- notifier


def test_real_notifier_quotes_and_never_raises():
    argv = osascript_argv('say "hi"', "back\\slash")
    assert argv[:2] == ("osascript", "-e")
    assert '"say \\"hi\\""' in argv[2] and '"back\\\\slash"' in argv[2]
    DesktopNotifier(platform="linux").notify("t", "m")  # a no-op elsewhere


# --------------------------------------------------------------------------- CLI: AC-1, preflight

PIPELINE = """\
settings:
  run_budget_usd: 5
  agents: {T: [s, x, worker, stub-worker, judge]}
stages:
  - id: only
    title: "One stage through the real adapters"
    entry: [full]
    budget_usd: 1
    steps:
      - id: work
        kind: agent
        agent: stub-worker
        agent_role: T
        prompt: work
        tools: [Read, Edit, Write]
        writes: true
      - {id: tests, kind: script, script: tests}
    gate: {kind: tests, steps: [tests]}
"""

PROMPT = "Do the work for ${run_id} ${scope} ${stage} ${step} r${round} in ${worktree}.\n" + \
    "${verdict_path} ${inputs} ${story_key} ${story_title} ${story_sources} ${story_touches}\n" + \
    "${epics_path}\n${feedback}\n"  # fmt: skip


@pytest.fixture
def sdlc_repo(repo, stubs, monkeypatch) -> Path:
    """The temp repo with a one-stage pipeline, its prompt, and an `sdlc-commands` block."""
    ok = shlex.join([sys.executable, "-c", "print('tests ok')"])
    (repo / "AGENTS.md").write_text(
        _agents(f"```yaml sdlc-commands\ntests:\n  - {ok}\n```\n"), encoding="utf-8"
    )
    (repo / "sdlc" / "prompts").mkdir(parents=True)
    (repo / "sdlc" / "pipeline.yaml").write_text(PIPELINE, encoding="utf-8")
    (repo / "sdlc" / "prompts" / "work.md").write_text(PROMPT, encoding="utf-8")
    git(repo, "add", "-A")
    git(repo, "commit", "--quiet", "-m", "sdlc")
    git(repo, "push", "--quiet", "origin", "HEAD:refs/heads/main")
    monkeypatch.setenv("STUB_CLAUDE_WRITE", "out.txt")
    # AD-23: the credential preflight must not see the developer's own settings or keys.
    home = repo.parent / "home"
    home.mkdir(exist_ok=True)
    monkeypatch.setenv("HOME", str(home))
    for name in (*CREDENTIAL_VARS, "CLAUDE_CONFIG_DIR"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setattr(real, "MANAGED_SETTINGS", {})
    return repo


def test_real_run_one_stage(sdlc_repo, stubs, capsys):
    """AC-1: a real run through the real adapters, persisted, and shown by `sdlc status`."""
    repo = sdlc_repo
    assert main(["--repo", str(repo), "run", "--pipeline", str(repo / "sdlc/pipeline.yaml")]) == 0
    runs = repo / "_bmad-output" / "sdlc" / "runs"
    (run_dir,) = [p for p in runs.iterdir() if p.is_dir()]
    state = json.loads((run_dir / "state.json").read_text(encoding="utf-8"))
    assert state["status"] == "passed" and state["dry_run"] is False
    branch = f"sdlc/{run_dir.name}"
    wt = repo.parent / "work-worktrees" / branch.replace("/", "__")
    assert (wt / "out.txt").exists()
    assert git(wt, "log", "-1", "--format=%s") == f"sdlc({run_dir.name}): stage:only/work r1"
    assert git(repo, "rev-parse", f"sdlc/{run_dir.name}/only^{{commit}}") == git(
        wt, "rev-parse", "HEAD"
    )
    (call,) = calls(stubs[1], "claude")
    assert Path(call["cwd"]).resolve() == wt.resolve()
    assert call["env"]["TMPDIR"].endswith("/.sdlc/tmp")
    assert "Do the work for" in call["stdin"]
    capsys.readouterr()
    assert main(["--repo", str(repo), "status"]) == 0
    out = capsys.readouterr().out
    assert run_dir.name in out and "passed" in out and "dry-run" not in out


def test_real_run_escalates_then_resumes(sdlc_repo, stubs, tmp_path, monkeypatch, capsys):
    """A failing tests gate escalates through the real gh and notifier; resume uses them too."""
    repo = sdlc_repo
    bad = shlex.join([sys.executable, "-c", "raise SystemExit(1)"])
    (repo / "AGENTS.md").write_text(
        _agents(f"```yaml sdlc-commands\ntests:\n  - {bad}\n```\n"), encoding="utf-8"
    )
    git(repo, "commit", "--quiet", "-am", "red tests")
    git(repo, "push", "--quiet", "origin", "HEAD:refs/heads/main")
    osa = stubs[0] / "osascript"  # no real desktop notification from the test suite
    osa.write_text(
        f"#!{sys.executable}\nimport json, os, sys\n"
        "open(os.environ['STUB_LOG'], 'a').write(json.dumps({'tool': 'osascript', "
        "'argv': sys.argv[1:]}) + '\\n')\n",
        encoding="utf-8",
    )
    osa.chmod(0o755)
    # The notifier is macOS-only; force it on so CI on Linux exercises the same path.
    real_init = DesktopNotifier.__init__
    monkeypatch.setattr(
        DesktopNotifier, "__init__", lambda self, platform=None: real_init(self, "darwin")
    )
    gh_config(tmp_path, monkeypatch, issues=[])
    assert main(["--repo", str(repo), "run"]) == 2
    (run_dir,) = [p for p in (repo / "_bmad-output" / "sdlc" / "runs").iterdir() if p.is_dir()]
    state = json.loads((run_dir / "state.json").read_text(encoding="utf-8"))
    assert state["status"] == "escalated" and state["escalations"][0]["issue"] == 7
    log = stubs[1]
    assert len(calls(log, "claude")) == 3  # max_rounds
    assert len(calls(log, "osascript")) == 1
    assert len([c for c in calls(log, "gh") if c["argv"][:2] == ["issue", "create"]]) == 1
    capsys.readouterr()
    assert main(["--repo", str(repo), "resume", run_dir.name]) == 2  # real ports again
    assert len(calls(log, "claude")) == 6
    # The core keeps the recorded issue, so a re-escalation opens no second issue.
    assert len([c for c in calls(log, "gh") if c["argv"][:2] == ["issue", "create"]]) == 1


def test_real_preflight(sdlc_repo, tmp_path, monkeypatch, capsys):
    """`gh` missing from PATH: exit 1 naming it, and no run dir."""
    only = tmp_path / "only-bin"
    only.mkdir()
    _install(only, "claude", "claude_stub.py")
    git_exe = shutil.which("git")
    assert git_exe
    (only / "git").symlink_to(git_exe)
    monkeypatch.setenv("PATH", str(only))
    assert main(["--repo", str(sdlc_repo), "run"]) == 1
    err = capsys.readouterr().err
    assert "gh not found" in err and len(err.strip().splitlines()) == 1
    assert not (sdlc_repo / "_bmad-output" / "sdlc" / "runs").exists()


def test_real_preflight_reports_every_problem(sdlc_repo, tmp_path, monkeypatch, capsys):
    """gh unauthenticated, too old, origin removed, no user.email: one line each."""
    gh_config(tmp_path, monkeypatch, auth_exit=1, version="gh version 2.30.0 (2023-01-01)")
    git(sdlc_repo, "remote", "remove", "origin")
    git(sdlc_repo, "config", "--unset", "user.email")
    assert main(["--repo", str(sdlc_repo), "run"]) == 1
    lines = capsys.readouterr().err.strip().splitlines()
    assert len(lines) == 4, lines
    text = "\n".join(lines)
    for want in ("gh auth status failed", "gh 2.40 or newer", "origin not found", "user.email"):
        assert want in text
    assert not (sdlc_repo / "_bmad-output" / "sdlc" / "runs").exists()


def test_real_preflight_dirty_checkout(sdlc_repo, capsys):
    (sdlc_repo / "stray.txt").write_text("x\n", encoding="utf-8")
    assert main(["--repo", str(sdlc_repo), "run"]) == 1
    assert "not clean" in capsys.readouterr().err
    assert not (sdlc_repo / "_bmad-output" / "sdlc" / "runs").exists()


# --------------------------------------------------------------------------- live (opt-in)


@pytest.mark.live
@pytest.mark.skipif(os.environ.get("SDLC_LIVE") != "1", reason="paid call; set SDLC_LIVE=1")
async def test_live_claude_one_call(tmp_path):
    """One real `claude -p` call (about $0.05) to confirm the `--output-format json` shape."""
    # AD-23: the same credential check as the preflight, so the call uses the subscription.
    assert credential_problems(ORCH.parent) == []
    argv = build_argv(budget_usd=0.25, allowed_tools=("Read",), restrict_tools=True)
    inv = Invocation(key=KEY, argv=argv, stdin="Reply with the single word: ok", cwd=str(tmp_path),
                     timeout_s=180)  # fmt: skip
    result = await ClaudeCli().run(inv)
    assert result.subtype == "success" and not result.is_error
    assert result.num_turns >= 1 and result.result
