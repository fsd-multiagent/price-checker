"""AD-26: path tiers and owner-review proposals. One test per row of the I/O
matrix of spec-orchestrator-path-tiers-proposals (T-1), with fakes, the stub `gh` and real `git`
in temporary repos. No paid call and no GitHub write.
"""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest
from conftest import FIXTURES, PIPELINE_PATH, PROMPTS, REPO, make_dry_run, pipeline_text, run_dry
from test_real_adapters import calls, commit_file, gh_config, git, repo, stubs  # noqa: F401
from test_usage import legacy_run

from sdlc import cli
from sdlc.adapters.commands import AGENTS_MD, apply_commands_block, parse_commands
from sdlc.adapters.fake import FakeGh, FakeGit, FixtureBook, fake_ports
from sdlc.adapters.gh import GhCli
from sdlc.adapters.git import GitCli
from sdlc.app.loop import Orchestrator, format_status
from sdlc.core.models import ProposalCaptured, ProposalStatus, RunKind
from sdlc.core.pipeline import load_pipeline
from sdlc.core.proposals import (
    BODY_LIMIT,
    OWNER_REVIEW_LABEL,
    change_key,
    factory_rules,
    fenced,
    file_diff,
    in_tier,
    issue_text,
    proposal_marker,
)
from sdlc.core.state import apply
from sdlc.ports import AdapterError
from sdlc.store import RunStore

LEARN = "learn:1-1-ean-input"
HOOK = ".claude/hooks/" + "guard.py"  # an owner-only file
OWNER_ONLY = (".claude/", ".github/CODEOWNERS", "docs/source-register.md")
PROTECTED = ("sdlc/", "orchestrator/", "_bmad/custom/", "docs/standards/")

BASE = """# Project

## Running and verifying

  ```yaml sdlc-commands
  tests:
    - uv run --directory orchestrator pytest -q
  ```

## Conventions

- Keep it short.
"""
AGENT = BASE.replace(
    "    - uv run --directory orchestrator pytest -q\n",
    "    - uv run --directory orchestrator pytest -q\n    - npm --prefix web test\n",
).replace("- Keep it short.", "- Keep it short. An agent's doc line.")


def _writes(dr, step_key: str, files: dict[str, str], **entry) -> None:
    """A step that writes `files` (path -> text) into its worktree."""
    book = dr.ports.claude.book
    texts = {f"inline:{rel}": text for rel, text in files.items()}
    original = book.text
    book.text = lambda rel: texts[rel] if rel in texts else original(rel)  # type: ignore[method-assign]
    book.steps[step_key] = {"writes": {rel: f"inline:{rel}" for rel in files}, **entry}


def _agents_md(dr, at_point: str, on_main: str | None = None) -> None:
    """`AGENTS.md` at the branch point, on origin/main, and in every worktree that has not
    written it (the fake fs has no checkout)."""
    git_, fs = dr.ports.git, dr.ports.fs
    git_.show_files[AGENTS_MD] = on_main if on_main is not None else at_point
    git_.show_at[(git_.point, AGENTS_MD)] = at_point
    read = fs.read_text

    def read_text(path: str) -> str | None:
        if path.endswith(f"/{AGENTS_MD}") and path not in fs.files:
            return at_point
        return read(path)

    fs.read_text = read_text  # type: ignore[method-assign]


def _commit_of(dr, suffix: str) -> list[str]:
    git_ = dr.ports.git
    (sha,) = [c[3] for c in git_.calls if c[0] == "commit" and c[2].endswith(suffix)]
    return git_.committed[sha]


# --------------------------------------------------------------------------- settings


def test_proposals_settings_tiers(pipeline):
    s = pipeline.settings
    assert s.owner_only_paths == OWNER_ONLY
    assert s.protected_paths == PROTECTED
    assert s.review_paths == (".github/workflows/",)
    assert in_tier(".claude/settings.json", s.owner_only_paths)
    assert in_tier("docs/source-register.md", s.owner_only_paths)
    assert not in_tier("docs/source-register.md.bak", s.owner_only_paths)
    assert not in_tier("sdlcx/a", s.protected_paths) and in_tier("sdlc/a", s.protected_paths)
    assert in_tier("a/b", ("a",)) and not in_tier("ab", ("a",))


# --------------------------------------------------------------------------- controls


async def test_proposals_controls_are_never_pinned(tmp_path):
    """AD-26 as amended: owner-only paths are not copied in from origin/main around steps."""
    dr = make_dry_run(tmp_path)
    assert await run_dry(dr) == 0
    assert not hasattr(dr.ports.git, "restore_from")
    assert not [c for c in dr.ports.git.calls if c[0] == "restore_from"]


async def test_proposals_reviewer_owner_only_edit_fails_the_reviewer(tmp_path):
    dr = make_dry_run(tmp_path)
    _writes(dr, "story:1-1-ean-input/code_review/r1", {HOOK: "weakened\n"})
    assert await run_dry(dr) == 0  # round 2 passes
    (rec,) = [
        r
        for r in dr.state.steps
        if r.scope == "story:1-1-ean-input" and r.step == "code_review" and r.round == 1
    ]
    assert rec.status.value == "failed" and HOOK in (rec.reason or "") + "".join(
        x for x in dr.lines if "reviewer changed files" in x
    )
    assert dr.unit("story:1-1-ean-input", "build").round == 2


# --------------------------------------------------------------------------- owner-only edit


async def test_proposals_owner_only_edit(tmp_path):
    dr = make_dry_run(tmp_path)
    _writes(dr, f"{LEARN}/keeper", {"docs/source-register.md": "| x | Approved |\n"})
    assert await run_dry(dr) == 0
    assert dr.state.proposals == ()
    assert "docs/source-register.md" not in _commit_of(dr, f"{LEARN}/keeper r1")
    events = [e for e in dr.events() if e["type"] == "paths_reverted" and e["scope"] == LEARN]
    assert events and "docs/source-register.md" in events[0]["paths"]
    assert not [c for c in dr.ports.git.calls if c[0] == "staged_diff"]


# --------------------------------------------------------------------------- protected edit


async def test_proposals_protected_edit_becomes_pr(tmp_path):
    dr = make_dry_run(tmp_path)
    files = {"sdlc/prompts/qa.md": "better prompt\n", "sdlc/prompts/new.md": "new prompt\n"}
    _writes(dr, f"{LEARN}/keeper", files)
    assert await run_dry(dr) == 0
    committed = _commit_of(dr, f"{LEARN}/keeper r1")
    assert not [p for p in committed if p.startswith("sdlc/")]
    (prop,) = dr.state.proposals
    assert set(prop.paths) == set(files) and prop.scope == LEARN
    assert prop.agent_role == "Aglaia (Knowledge Keeper)"
    assert prop.status is ProposalStatus.DELIVERED and prop.kind == "pr"
    patch = (dr.runs_dir / dr.state.run_id / prop.patch).read_text(encoding="utf-8")
    assert "sdlc/prompts/new.md" in patch and "+new prompt" in patch  # the new file is in it
    gh = dr.ports.gh
    (item,) = gh.proposal_items
    assert item["kind"] == "pr" and item["head"] == f"proposal/{prop.sha256[:8]}"
    assert item["body"].startswith(proposal_marker(prop.sha256))
    assert gh.labels[item["number"]] == {OWNER_REVIEW_LABEL}
    assert prop.number == item["number"]
    assert not [c for c in gh.calls if c[0] == "merge" and c[1] == item["number"]]
    pushed = [c for c in dr.ports.git.calls if c[0] == "push" and c[1].startswith("proposal/")]
    assert [(c[1], c[3]) for c in pushed] == [(item["head"], True)]  # --force: ours alone
    (applied,) = dr.ports.git.applied
    assert applied[1] == patch
    # The proposal worktree and local branch are removed; the remote branch carries the PR.
    removed = [c for c in dr.ports.git.calls if c[0] == "remove_worktree"]
    assert [(c[2], c[3]) for c in removed] == [(item["head"], False)]


async def test_proposals_protected_edit_real_git(repo):  # noqa: F811
    """The real adapters: the agent's commit has neither file; the patch, new file included,
    applies on a proposal branch cut from origin/main."""
    g = GitCli(repo)
    commit_file(repo, "sdlc/prompts/qa.md", "qa\n", "prompts")
    git(repo, "push", "--quiet", "origin", "HEAD:refs/heads/main")
    await g.fetch()
    path, _ = await g.ensure_worktree("learn/1-1-a", "origin/main")
    wt = Path(path)
    (wt / "sdlc" / "prompts" / "qa.md").write_text("qa, better\n", encoding="utf-8")
    (wt / "sdlc" / "prompts" / "new.md").write_text("new\n", encoding="utf-8")
    (wt / "docs.md").write_text("doc\n", encoding="utf-8")
    protected = ("sdlc/prompts/new.md", "sdlc/prompts/qa.md")
    assert set(await g.changed_paths(path)) >= set(protected)
    staged = await g.staged_diff(path, protected)
    patch = staged.patch
    assert [p for p, _ in staged.entries] == list(protected)
    assert all(blob and blob.startswith("100644 ") for _, blob in staged.entries)
    assert "new file mode" in patch and "+qa, better" in patch and "index " in patch
    await g.restore_paths(path, protected)
    await g.commit_all(path, "learn commit")
    assert git(wt, "show", "--name-only", "--format=", "HEAD") == "docs.md"
    prop_path, _ = await g.ensure_worktree("proposal/abcdef12", "origin/main")
    assert await g.apply_3way(prop_path, patch) is True
    pw = Path(prop_path)
    assert (pw / "sdlc" / "prompts" / "new.md").read_text(encoding="utf-8") == "new\n"
    assert (pw / "sdlc" / "prompts" / "qa.md").read_text(encoding="utf-8") == "qa, better\n"


# --------------------------------------------------------------------------- gate block


def test_proposals_apply_commands_block():
    spliced = apply_commands_block(BASE, AGENT)
    assert spliced is not None
    assert parse_commands(spliced) == parse_commands(AGENT)
    assert "An agent's doc line" not in spliced
    assert spliced.replace("    - npm --prefix web test\n", "") == BASE
    assert apply_commands_block(BASE, BASE) is None
    assert apply_commands_block(BASE, "# no block\n") is None
    assert apply_commands_block(BASE, None) is None


async def test_proposals_gate_block_proposal(tmp_path):
    dr = make_dry_run(tmp_path)
    _agents_md(dr, BASE)
    _writes(dr, f"{LEARN}/keeper", {AGENTS_MD: AGENT})
    assert await run_dry(dr) == 0
    (prop,) = dr.state.proposals
    assert prop.paths == (AGENTS_MD,)
    patch = (dr.runs_dir / dr.state.run_id / prop.patch).read_text(encoding="utf-8")
    # Applied to origin/main's file, the patch gives main's file with only the block spliced in.
    work = tmp_path / "apply"
    work.mkdir()
    subprocess.run(["git", "init", "--quiet"], cwd=work, check=True)
    (work / AGENTS_MD).write_text(BASE, encoding="utf-8")
    subprocess.run(["git", "apply", "-"], cwd=work, input=patch, text=True, check=True)
    assert (work / AGENTS_MD).read_text(encoding="utf-8") == apply_commands_block(BASE, AGENT)
    # The learn branch itself kept main's block (ADR 0021).
    written = dr.ports.fs.files[f"{dr.state.scope(LEARN).worktree}/{AGENTS_MD}"]
    assert parse_commands(written) == parse_commands(BASE)


async def test_proposals_gate_block_changed_on_main_is_not_undone(tmp_path):
    """The owner changed the block on main mid-run; the agent kept its branch point's block:
    nothing to propose (compared with the branch point, ADR 0023)."""
    dr = make_dry_run(tmp_path)
    on_main = AGENT.replace("- Keep it short. An agent's doc line.", "- Keep it short.")
    _agents_md(dr, BASE, on_main)
    _writes(dr, f"{LEARN}/keeper", {AGENTS_MD: BASE.replace("short.", "short. Doc.")})
    assert await run_dry(dr) == 0
    assert dr.state.proposals == ()


@pytest.mark.parametrize("change", ["deleted", "added"])
async def test_proposals_gate_block_deleted_or_added_proposes_the_whole_file(tmp_path, change):
    dr = make_dry_run(tmp_path)
    no_block = "# Project\n\n## Conventions\n\n- Keep it short.\n"
    point, agent = (BASE, no_block) if change == "deleted" else (no_block, AGENT)
    _agents_md(dr, point)
    _writes(dr, f"{LEARN}/keeper", {AGENTS_MD: agent})
    assert await run_dry(dr) == 0
    (prop,) = dr.state.proposals
    assert prop.paths == (AGENTS_MD,)
    patch = (dr.runs_dir / dr.state.run_id / prop.patch).read_text(encoding="utf-8")
    work = tmp_path / "apply"
    work.mkdir()
    subprocess.run(["git", "init", "--quiet"], cwd=work, check=True)
    (work / AGENTS_MD).write_text(point, encoding="utf-8")
    subprocess.run(["git", "apply", "-"], cwd=work, input=patch, text=True, check=True)
    assert (work / AGENTS_MD).read_text(encoding="utf-8") == agent  # the whole file


def test_proposals_file_diff_without_final_newline(tmp_path):
    old, new = "a\nb", "a\nc"
    patch = file_diff("f.txt", old, new)
    (tmp_path / "f.txt").write_text(old, encoding="utf-8")
    subprocess.run(["git", "init", "--quiet"], cwd=tmp_path, check=True)
    subprocess.run(["git", "apply", "-"], cwd=tmp_path, input=patch, text=True, check=True)
    assert (tmp_path / "f.txt").read_text(encoding="utf-8") == new


# --------------------------------------------------------------------------- workflow edit


async def test_proposals_workflow_edit(tmp_path):
    dr = make_dry_run(tmp_path)
    _writes(dr, f"{LEARN}/keeper", {".github/workflows/ci.yml": "on: push\n"})
    assert await run_dry(dr) == 0
    (prop,) = dr.state.proposals
    assert prop.review and prop.kind == "issue"
    assert ".github/workflows/ci.yml" not in _commit_of(dr, f"{LEARN}/keeper r1")
    (item,) = dr.ports.gh.proposal_items
    assert item["kind"] == "issue" and item["body"].startswith(proposal_marker(prop.sha256))
    assert "```diff\n" in item["body"] and "+on: push" in item["body"]
    assert dr.ports.gh.labels[item["number"]] == {OWNER_REVIEW_LABEL}
    git_ = dr.ports.git
    assert not [c for c in git_.calls if c[0] in ("push", "ensure_worktree")
                and c[1].startswith("proposal/")]  # fmt: skip
    assert not [c for c in git_.calls if c[0] == "apply_3way"]


# --------------------------------------------------------------------------- apply conflict


async def test_proposals_apply_conflict(tmp_path):
    dr = make_dry_run(tmp_path)
    _writes(dr, f"{LEARN}/keeper", {"sdlc/prompts/qa.md": "better\n"})
    dr.ports.git.apply_ok = False
    assert await run_dry(dr) == 0
    (prop,) = dr.state.proposals
    assert prop.kind == "issue" and "does not apply" in (prop.reason or "")
    git_ = dr.ports.git
    wt = next(c[1] for c in git_.calls if c[0] == "apply_3way")
    after = git_.calls[[c[0] for c in git_.calls].index("apply_3way") + 1 :]
    assert ("reset_hard", wt, "origin/main") in after  # the proposal worktree is reset
    assert ("remove_worktree", wt, prop.branch, True) in after
    assert not [c for c in git_.calls if c[0] == "push" and c[1].startswith("proposal/")]
    (item,) = dr.ports.gh.proposal_items
    assert item["kind"] == "issue" and "+better" in item["body"]


async def test_proposals_apply_conflict_real_git(repo):  # noqa: F811
    g = GitCli(repo)
    commit_file(repo, "sdlc/p.md", "one\n", "p")
    git(repo, "push", "--quiet", "origin", "HEAD:refs/heads/main")
    await g.fetch()
    path, _ = await g.ensure_worktree("learn/1-1-a", "origin/main")
    (Path(path) / "sdlc" / "p.md").write_text("agent\n", encoding="utf-8")
    patch = (await g.staged_diff(path, ("sdlc/p.md",))).patch
    commit_file(repo, "sdlc/p.md", "main moved\n", "main")  # the same line changed on main
    git(repo, "push", "--quiet", "origin", "HEAD:refs/heads/main")
    await g.fetch()
    prop_path, _ = await g.ensure_worktree("proposal/12345678", "origin/main")
    assert await g.apply_3way(prop_path, patch) is False
    await g.reset_hard(prop_path, "origin/main")
    assert git(Path(prop_path), "status", "--porcelain") == ""


# --------------------------------------------------------------------------- dedupe


async def test_proposals_dedupe(tmp_path):
    dr = make_dry_run(tmp_path)
    same = {"sdlc/prompts/qa.md": "better\n"}
    _writes(dr, f"{LEARN}/keeper", same)
    _writes(dr, "learn:1-2-retailer-config/keeper", same)  # the same patch again
    assert await run_dry(dr) == 0
    assert len(dr.state.proposals) == 1 and len(dr.ports.gh.proposal_items) == 1
    captured = [e for e in dr.events() if e["type"] == "proposal_captured"]
    assert len(captured) == 2  # captured twice, recorded once
    # Applying the capture again (a resume) changes nothing.
    again = ProposalCaptured.model_validate({**captured[0], "seq": 999})
    assert apply(dr.orch.p, dr.state, again).proposals == dr.state.proposals


async def test_proposals_same_change_from_two_heads_has_one_key(repo):  # noqa: F811
    """The key is the normalised change, not the patch: the same edit cut from two different
    heads (different pre-images, different `index` lines) is one proposal; fake and real
    agree on what counts."""
    g = GitCli(repo)
    commit_file(repo, "sdlc/p.md", "one\n", "p")
    git(repo, "push", "--quiet", "origin", "HEAD:refs/heads/main")
    await g.fetch()
    first, _ = await g.ensure_worktree("learn/1-1-a", "origin/main")
    commit_file(repo, "sdlc/p.md", "two\n", "main moved")
    git(repo, "push", "--quiet", "origin", "HEAD:refs/heads/main")
    await g.fetch()
    second, _ = await g.ensure_worktree("learn/1-2-b", "origin/main")
    staged = []
    for wt in (first, second):
        (Path(wt) / "sdlc" / "p.md").write_text("agent\n", encoding="utf-8")
        staged.append(await g.staged_diff(wt, ("sdlc/p.md",)))
    assert staged[0].patch != staged[1].patch
    assert change_key(staged[0].entries) == change_key(staged[1].entries)
    (Path(second) / "sdlc" / "p.md").write_text("other\n", encoding="utf-8")
    other = await g.staged_diff(second, ("sdlc/p.md",))
    assert change_key(other.entries) != change_key(staged[0].entries)
    fake = FakeGit(FixtureBook(FIXTURES), "/r")
    for wt in ("/w1", "/w2"):
        fake.fs.write_text(f"{wt}/sdlc/p.md", "agent\n")
    a, b = [await fake.staged_diff(wt, ("sdlc/p.md",)) for wt in ("/w1", "/w2")]
    assert change_key(a.entries) == change_key(b.entries)


async def test_proposals_dedupe_closed_by_the_owner(tmp_path):
    """The owner closed the PR in an earlier run: the marker finds it, nothing is reopened or
    created, and there is no notification."""
    dr = make_dry_run(tmp_path)
    _writes(dr, f"{LEARN}/keeper", {"sdlc/prompts/qa.md": "better\n"})
    first = make_dry_run(tmp_path / "first")
    _writes(first, f"{LEARN}/keeper", {"sdlc/prompts/qa.md": "better\n"})
    assert await run_dry(first) == 0
    (closed,) = first.ports.gh.proposal_items
    closed["state"] = "CLOSED"
    dr.ports.gh.proposal_items.append(closed)
    assert await run_dry(dr) == 0
    (prop,) = dr.state.proposals
    assert prop.number == closed["number"] and prop.reason == "found by its marker"
    assert dr.ports.gh.proposal_items == [closed] and closed["state"] == "CLOSED"
    assert not [c for c in dr.ports.git.calls if c[0] == "push" and c[1].startswith("proposal/")]
    assert not [n for n in dr.ports.notifier.sent if "owner review" in n[0]]


async def test_proposals_dedupe_real_gh(stubs, tmp_path, monkeypatch):  # noqa: F811
    _, log = stubs
    sha = "ab" * 32
    closed = {"number": 12, "body": f"{proposal_marker(sha)}\nold", "pr": True}
    stray = {"number": 3, "body": "quotes <!-- sdlc-proposal:other -->", "pr": False}
    gh_config(tmp_path, monkeypatch, api_items=[stray, closed])
    gh = GhCli(tmp_path)
    ref = await gh.find_proposal(sha)
    assert ref is not None and (ref.kind, ref.number, ref.created) == ("pr", 12, False)
    ref = await gh.ensure_proposal_issue(sha, "t", "b")  # found again: never created
    assert (ref.kind, ref.number) == ("pr", 12)
    argvs = [c["argv"] for c in calls(log, "gh")]
    assert not [a for a in argvs if a[1] in ("create", "reopen", "edit")]
    (listing, _) = [a for a in argvs if a[0] == "api"]
    assert "--paginate" in listing
    url = next(a for a in listing if a.startswith("repos/"))
    assert "labels=owner-review" in url and "state=all" in url
    # A new proposal after the caller's own lookup: no second listing, label, then the PR.
    gh_config(tmp_path, monkeypatch)
    before = len([c for c in calls(log, "gh") if c["argv"][0] == "api"])
    ref = await gh.ensure_proposal_pr("cd" * 32, "proposal/cdcdcdcd", "t", "b", looked_up=True)
    assert ref.created and ref.kind == "pr"
    argvs = [c["argv"] for c in calls(log, "gh")]
    assert len([a for a in argvs if a[0] == "api"]) == before
    (label,) = [a for a in argvs if a[:2] == ["label", "create"]]
    assert label[2] == OWNER_REVIEW_LABEL and "--force" in label
    (create,) = [a for a in argvs if a[:2] == ["pr", "create"]]
    assert create[create.index("--label") + 1] == OWNER_REVIEW_LABEL
    assert create[create.index("--body") + 1].startswith(proposal_marker("cd" * 32))


# --------------------------------------------------------------------------- merge refusal


async def test_proposals_merge_refusal_fake():
    gh = FakeGh()
    ref = await gh.ensure_proposal_pr("ef" * 32, "proposal/efefefef", "t", "b")
    with pytest.raises(AdapterError, match=OWNER_REVIEW_LABEL):
        await gh.merge(ref.number, "deadbeef")


async def test_proposals_merge_refusal_real_gh(stubs, tmp_path, monkeypatch):  # noqa: F811
    _, log = stubs
    view = {"state": "OPEN", "labels": [{"name": OWNER_REVIEW_LABEL}]}
    gh_config(tmp_path, monkeypatch, view=[view])
    with pytest.raises(AdapterError, match="owner-review"):
        await GhCli(tmp_path, poll_s=0.01).merge(9, "deadbeef")
    assert not [c for c in calls(log, "gh") if c["argv"][:2] == ["pr", "merge"]]  # no --auto
    gh_config(tmp_path, monkeypatch, view=["error"])
    with pytest.raises(AdapterError, match="cannot read PR #9"):
        await GhCli(tmp_path, poll_s=0.001).merge(9, "deadbeef")
    assert not [c for c in calls(log, "gh") if c["argv"][:2] == ["pr", "merge"]]


async def test_proposals_merge_refusal_rechecked_on_every_poll(stubs, tmp_path, monkeypatch):  # noqa: F811
    """Labelled while auto-merge waits: auto-merge is turned off and the merge refused."""
    _, log = stubs
    labelled = {"state": "OPEN", "labels": [{"name": OWNER_REVIEW_LABEL}]}
    gh_config(tmp_path, monkeypatch, view=[{"state": "OPEN", "labels": []}, labelled])
    with pytest.raises(AdapterError, match="owner-review"):
        await GhCli(tmp_path, poll_s=0.01).merge(9, "deadbeef", paths=[])
    merges = [c["argv"] for c in calls(log, "gh") if c["argv"][:2] == ["pr", "merge"]]
    assert merges[0][3] == "--auto" and merges[-1] == ["pr", "merge", "9", "--disable-auto"]


async def test_proposals_merge_refusal_fails_the_step(tmp_path):
    dr = make_dry_run(tmp_path)
    gh = dr.ports.gh
    real_pr = gh.ensure_pr

    async def labelled(head, base, title, body, labels=()):
        number = await real_pr(head, base, title, body, labels)
        if head == "story/1-1-ean-input":
            gh.labels[number] = {OWNER_REVIEW_LABEL}
        return number

    gh.ensure_pr = labelled  # type: ignore[method-assign]
    assert await run_dry(dr) == 2
    scope = "story:1-1-ean-input"
    merges = [r for r in dr.state.steps if r.scope == scope and r.step == "merge_pr"]
    assert merges and all(r.status.value == "failed" for r in merges)
    assert dr.state.scope(scope).merged_sha is None


# --------------------------------------------------------------------------- retry prompt


async def test_proposals_retry_prompt(tmp_path):
    dr = make_dry_run(tmp_path)
    _agents_md(dr, BASE)
    git_, fs = dr.ports.git, dr.ports.fs
    at_diff: list[str | None] = []
    diff = git_.diff

    async def snapshot(worktree, since, exclude=()):
        at_diff.append(fs.read_text(f"{worktree}/{AGENTS_MD}"))
        return await diff(worktree, since, exclude)

    git_.diff = snapshot  # type: ignore[method-assign]
    files = {
        "sdlc/pipeline.yaml": "weaker\n",
        HOOK: "x\n",
        ".github/workflows/ci.yml": "on: push\n",
        "backend/App.java": "class App {}\n",
        AGENTS_MD: AGENT,
    }
    _writes(dr, "story:1-1-ean-input/dev/r1", files, status="blocked")
    assert await run_dry(dr) == 0
    diffs = [c for c in dr.ports.git.calls if c[0] == "diff"]
    tiers = set(OWNER_ONLY) | set(PROTECTED) | {".github/workflows/"}
    assert diffs and all(set(c[3]) >= tiers for c in diffs)
    # The block went back to the scope head's before the diff; other AGENTS.md lines stay.
    (agents,) = at_diff
    assert parse_commands(agents) == parse_commands(BASE) and "An agent's doc line" in agents
    (r2,) = [
        inv
        for inv in dr.ports.claude.invocations
        if inv.key.scope == "story:1-1-ean-input" and inv.key.step == "dev" and inv.key.round == 2
    ]
    rel = next(line.split("- ", 1)[1] for line in r2.stdin.splitlines() if "discarded" in line)
    patch = dr.ports.fs.files[rel]
    assert "backend/App.java" in patch
    assert "sdlc/pipeline.yaml" not in patch and HOOK not in patch and "workflows" not in patch
    assert dr.state.proposals == ()  # a failed round proposes nothing


async def test_proposals_retry_prompt_real_git(repo):  # noqa: F811
    g = GitCli(repo)
    path, head = await g.ensure_worktree("story/1-1-a", "origin/main")
    wt = Path(path)
    (wt / "sdlc").mkdir()
    (wt / "sdlc" / "pipeline.yaml").write_text("weaker\n", encoding="utf-8")
    (wt / "a.txt").write_text("changed\n", encoding="utf-8")
    diff = await g.diff(path, head, exclude=PROTECTED)
    assert "a.txt" in diff and "sdlc/pipeline.yaml" not in diff


# --------------------------------------------------------------------------- notify, status


async def test_proposals_notification_and_status(tmp_path, capsys):
    dr = make_dry_run(tmp_path)
    _writes(dr, f"{LEARN}/keeper", {"orchestrator/src/x.py": "x = 1\n"})
    assert await run_dry(dr) == 0
    (prop,) = dr.state.proposals
    sent = [n for n in dr.ports.notifier.sent if "owner review" in n[0]]
    assert len(sent) == 1 and f"PR #{prop.number}" in sent[0][1]
    assert f"owner review: PR #{prop.number}" in format_status(dr.state)
    capsys.readouterr()
    assert cli.main(["--runs-dir", str(dr.runs_dir), "status", dr.state.run_id]) == 0
    assert f"owner review: PR #{prop.number}" in capsys.readouterr().out


async def test_proposals_delivery_failure_then_resume(tmp_path):
    dr = make_dry_run(tmp_path)
    _writes(dr, f"{LEARN}/keeper", {"sdlc/prompts/qa.md": "better\n"})
    gh = dr.ports.gh
    find = gh.find_proposal

    async def down(sha256):
        raise AdapterError("gh is down")

    gh.find_proposal = down  # type: ignore[method-assign]
    assert await run_dry(dr) == 0  # the run passes; the proposal waits for a resume
    (prop,) = dr.state.proposals
    assert prop.status is ProposalStatus.FAILED and "gh is down" in (prop.reason or "")
    assert "sdlc resume retries" in format_status(dr.state)
    failed = [n for n in dr.ports.notifier.sent if "not delivered" in n[1]]
    assert len(failed) == 1  # one notification for the failed proposal
    gh.find_proposal = find  # type: ignore[method-assign]
    orch = Orchestrator(dr.orch.p, dr.ports, dr.runs_dir, REPO, PROMPTS, str)
    assert await orch.resume(dr.state.run_id) == 0
    assert orch.state is not None
    (prop,) = orch.state.proposals
    assert prop.status is ProposalStatus.DELIVERED and prop.kind == "pr"
    assert orch.state.status.value == "passed"


async def test_proposals_patch_changing_nothing_on_main_becomes_an_issue(tmp_path):
    dr = make_dry_run(tmp_path)
    _writes(dr, f"{LEARN}/keeper", {"sdlc/prompts/qa.md": "better\n"})
    git_ = dr.ports.git
    commit = git_.commit_all

    async def nothing_on_proposal(worktree, message):
        return None if "/proposal/" in worktree else await commit(worktree, message)

    git_.commit_all = nothing_on_proposal  # type: ignore[method-assign]
    assert await run_dry(dr) == 0
    (prop,) = dr.state.proposals
    assert prop.kind == "issue" and "changes nothing" in (prop.reason or "")
    assert not [c for c in git_.calls if c[0] == "push" and c[1].startswith("proposal/")]
    wt = next(c[1] for c in git_.calls if c[0] == "apply_3way")
    after = git_.calls[[c[0] for c in git_.calls].index("apply_3way") + 1 :]
    assert ("reset_hard", wt, "origin/main") in after
    assert ("remove_worktree", wt, prop.branch, True) in after  # branch gone, remote too


# --------------------------------------------------------------------------- bodies, prompts


def _prop(paths, patch_rel="steps/1.proposal.patch"):
    from sdlc.core.models import Proposal

    return Proposal(sha256="0" * 64, scope=LEARN, seq=1, paths=tuple(paths), patch=patch_rel)


def test_proposals_issue_body_clips_on_file_boundaries(tmp_path):
    dr = make_dry_run(tmp_path)
    dr.orch.state = None
    from sdlc.core.state import initial_state

    state = initial_state(dr.orch.p, run_id="r1", kind=RunKind.FULL, at="t", dry_run=True)
    big = "+" + "x" * 1000 + "\n"
    chunks = [
        f"diff --git a/sdlc/f{i}.md b/sdlc/f{i}.md\n--- a/sdlc/f{i}.md\n+++ b/sdlc/f{i}.md\n"
        + "@@ -0,0 +1,30 @@\n"
        + big * 30
        for i in range(5)
    ]
    patch = "".join(chunks)
    paths = [f"sdlc/f{i}.md" for i in range(5)] + [f"docs/standards/x{i}.md" for i in range(80)]
    body = issue_text(state, _prop(paths), patch, None)
    assert len(body) <= BODY_LIMIT
    shown = body[body.index("```diff") :]
    kept = [c for c in chunks if c in shown]
    assert 0 < len(kept) < len(chunks)  # whole files only: no chunk is cut mid-hunk
    assert all(c.count(big) == 30 for c in kept)
    for i in range(len(kept), 5):
        assert f"- `sdlc/f{i}.md`" in body[body.index("Left out") :]
    assert "local run directory on the runner" in body
    assert "- and 35 more" in body  # 85 paths: the first 50 are named
    assert body.count("- `docs/standards/") == 45


def test_proposals_issue_body_fence_outlasts_backticks(tmp_path):
    from sdlc.core.state import initial_state

    dr = make_dry_run(tmp_path)
    state = initial_state(dr.orch.p, run_id="r1", kind=RunKind.FULL, at="t", dry_run=True)
    patch = "diff --git a/sdlc/a.md b/sdlc/a.md\n+````yaml\n+x\n+````\n"
    body = issue_text(state, _prop(["sdlc/a.md"]), patch, None)
    assert "`````diff\n" in body and body.rstrip().endswith("`````")
    assert fenced("a ``` b").startswith("````diff\n")


def test_proposals_every_writing_prompt_has_the_factory_rules(pipeline):
    writing = {s.prompt for st in pipeline.stages for s in st.steps if s.writes and s.prompt}
    assert writing
    for name in writing:
        text = (PROMPTS / f"{name}.md").read_text(encoding="utf-8")
        assert "${factory_rules}" in text, name
        assert "Factory changes (AD-26)" not in text, name  # rendered, never copied
    rules = factory_rules(pipeline.settings)
    for entry in (*OWNER_ONLY, *PROTECTED, ".github/workflows/"):
        assert f"`{entry}`" in rules


# --------------------------------------------------------------------------- legacy state


async def test_proposals_legacy_state(tmp_path):
    runs, run_id = legacy_run(tmp_path)
    p = load_pipeline(pipeline_text())
    state = RunStore(runs / run_id).load(p)
    assert state.proposals == ()
    assert json.loads((runs / run_id / "state.json").read_text()).get("proposals") is None
    ports = fake_ports(FixtureBook(FIXTURES), str(runs))
    orch = Orchestrator(p, ports, runs, REPO, PROMPTS, str)
    await orch.resume(run_id)
    assert orch.state is not None and orch.state.proposals == ()
    assert cli._load(PIPELINE_PATH)[1] == state.pipeline_sha256  # the tiers are settings


async def test_proposals_branch_force_push_and_cleanup_real_git(repo):  # noqa: F811
    g = GitCli(repo)
    await g.fetch()
    path, _ = await g.ensure_worktree("proposal/0badc0de", "origin/main")
    commit_file(Path(path), "sdlc/a.md", "first\n", "attempt 1")
    await g.push(path, "proposal/0badc0de", force=True)
    git(Path(path), "reset", "--quiet", "--hard", "origin/main")
    commit_file(Path(path), "sdlc/a.md", "second\n", "attempt 2")  # not a fast-forward
    await g.push(path, "proposal/0badc0de", force=True)
    with pytest.raises(AdapterError, match="not a proposal branch"):
        await g.push(path, "story/1-1-a", force=True)
    with pytest.raises(AdapterError, match="main"):
        await g.push(path, "main", force=True)
    await g.remove_worktree(path, "proposal/0badc0de", remote=True)
    assert not Path(path).exists()
    assert git(repo, "branch", "--list", "proposal/*") == ""
    assert git(repo, "ls-remote", "origin", "refs/heads/proposal/*") == ""
