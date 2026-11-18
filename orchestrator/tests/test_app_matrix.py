"""App tests with fake adapters: one test per row of the spec's I/O and edge-case matrix (T-1)."""

from __future__ import annotations

import re

import pytest
from conftest import (
    FIXTURES,
    PROMPTS,
    REPO,
    make_dry_run,
    pipeline_text,
    run_dry,
    statuses,
)

from sdlc import cli
from sdlc.adapters.fake import FakeCrash, FixtureBook, fake_ports
from sdlc.app.loop import Orchestrator
from sdlc.core.models import Status
from sdlc.core.pipeline import load_pipeline

STORIES = ("1-1-ean-input", "1-2-retailer-config", "1-3-compare-page")


async def test_matrix_happy_dry_run(tmp_path, capsys):
    dr = make_dry_run(tmp_path)
    assert await run_dry(dr) == 0
    state = dr.state
    assert state.status is Status.PASSED
    assert all(u.status is Status.PASSED for u in state.units)
    assert [s.key for s in state.stories] == list(STORIES)
    # Three stories went through stages 7 to 9, plus the epic retrospective.
    for key in STORIES:
        for stage in ("build", "integrate"):
            assert dr.unit(f"story:{key}", stage).status is Status.PASSED
        assert dr.unit(f"learn:{key}", "learn").status is Status.PASSED
    assert dr.unit("learn:epic-1", "retro").status is Status.PASSED

    # Two stories ran in parallel; the dependent one started only after both were merged.
    seq = {(r.scope, r.step): r.seq for r in state.steps}
    s1, s2, s3 = (f"story:{k}" for k in STORIES)
    assert seq[(s2, "dev")] < seq[(s1, "qa")]
    assert seq[(s3, "dev")] > max(seq[(s1, "merge_pr")], seq[(s2, "merge_pr")])

    # Every agent step printed its exact `claude -p` argument list (AD-8).
    assert any("$ claude -p --output-format json --max-budget-usd" in line for line in dr.lines)

    # `status` shows all passed.
    capsys.readouterr()
    assert cli.main(["--runs-dir", str(dr.runs_dir), "status", state.run_id]) == 0
    out = capsys.readouterr().out
    assert "status=passed" in out
    assert "escalated" not in out and "blocked" not in out

    # Fakes recorded git and gh calls; nothing was pushed to main; stages were tagged.
    git = dr.ports.git
    assert ("push", "main") not in git.calls
    assert f"sdlc/{state.run_id}/breakdown" in git.tags
    assert dr.ports.gh.issues == []

    # AD-21: bookkeeping paths are restored before the Developer's leftovers are committed.
    wt = state.scope(s1).worktree
    calls = [c for c in git.calls if c[0] in ("restore_paths", "commit") and c[1] == wt]
    dev_commit = next(
        i for i, c in enumerate(calls) if c[0] == "commit" and c[2].endswith(f"{s1}/dev r1")
    )
    assert dev_commit > 0 and calls[dev_commit - 1][0] == "restore_paths"
    # The reviewer that writes a verdict file never commits.
    assert not [c for c in git.calls if c[0] == "commit" and "/code_review " in c[2]]

    # merge_pr merges exactly the SHA that open_pr pushed.
    pushed = {c[1]: c[2] for c in git.calls if c[0] == "push"}
    branch_of_pr = {n: b for b, n in dr.ports.gh.prs.items()}
    merges = [c for c in dr.ports.gh.calls if c[0] == "merge"]
    assert merges
    for _, pr, sha in merges:
        assert sha == pushed[branch_of_pr[pr]]


async def test_matrix_gate_fails_then_passes(tmp_path):
    dr = make_dry_run(tmp_path, "gate-fails-then-passes")
    assert await run_dry(dr) == 0
    build = dr.unit("story:1-1-ean-input", "build")
    assert build.status is Status.PASSED and build.round == 2
    assert dr.state.counter("story:1-1-ean-input", "review_verdicts") == 1
    # Round 2 names the failed verdict and the discarded patch in the prompt.
    r2 = [
        inv
        for inv in dr.ports.claude.invocations
        if inv.key.scope == "story:1-1-ean-input" and inv.key.step == "dev" and inv.key.round == 2
    ]
    assert len(r2) == 1
    prompt = r2[0].stdin
    assert ".sdlc/feedback/r2/verdicts_story_1-1-ean-input_r1_review_verdicts.json" in prompt
    assert "CR-1 (C-7): Unparseable price is returned as 0" in prompt
    assert "discarded.patch" in prompt
    assert "_bmad-output/sdlc/" not in prompt  # AD-9


async def test_matrix_gate_fails_max_rounds(tmp_path):
    dr = make_dry_run(tmp_path, "gate-fails-max-rounds")
    assert await run_dry(dr) == 2
    s = dr.state
    build = dr.unit("story:1-1-ean-input", "build")
    assert build.status is Status.ESCALATED and build.round == 3
    assert build.escalated_gate == "review_verdicts"
    # The independent story finished; the dependent one and the learn queue items are blocked.
    assert dr.unit("story:1-2-retailer-config", "integrate").status is Status.PASSED
    assert dr.unit("learn:1-2-retailer-config", "learn").status is Status.PASSED
    assert dr.unit("story:1-3-compare-page", "build").status is Status.BLOCKED
    assert dr.unit("learn:epic-1", "retro").status is Status.BLOCKED
    assert s.status is Status.ESCALATED
    # Exactly one escalation issue, recorded in state (AD-13).
    assert len(dr.ports.gh.issues) == 1
    assert "<!-- sdlc:" in dr.ports.gh.issues[0]["body"]
    assert s.escalation("story:1-1-ean-input").issue == dr.ports.gh.issues[0]["number"]
    assert len(dr.ports.notifier.sent) == 1


async def test_matrix_unapproved_source(tmp_path):
    dr = make_dry_run(tmp_path, "unapproved-source")
    assert await run_dry(dr) == 2
    cel = dr.unit("story:1-2-cel-adapter", "build")
    assert cel.status is Status.BLOCKED
    assert cel.round == 0  # consumes no round
    assert "cel.ro" in (cel.reason or "")
    assert dr.unit("story:1-3-compare-page", "build").status is Status.BLOCKED
    assert dr.unit("story:1-1-ean-input", "integrate").status is Status.PASSED
    # A story whose only source is Approved runs.
    assert dr.unit("story:1-4-approved-adapter", "integrate").status is Status.PASSED
    assert dr.state.status is Status.BLOCKED
    assert dr.ports.gh.issues == []


async def test_matrix_agent_reports_blocked_is_a_failed_round(tmp_path):
    dr = make_dry_run(tmp_path, "agent-blocked")
    assert await run_dry(dr) == 0
    for key in ("1-1-ean-input", "1-2-retailer-config"):
        build = dr.unit(f"story:{key}", "build")
        assert build.status is Status.PASSED and build.round == 2
        assert dr.state.counter(f"story:{key}", "tests") == 1
    r1 = {
        r.scope: r
        for r in dr.state.steps
        if r.step == "dev" and r.round == 1 and r.scope != "story:1-3-compare-page"
    }
    assert r1["story:1-1-ean-input"].outcome == "blocked"
    assert r1["story:1-2-retailer-config"].outcome == "error"
    assert "error_max_budget_usd" in (r1["story:1-2-retailer-config"].reason or "")
    commits = [c[2] for c in dr.ports.git.calls if c[0] == "commit"]
    for key in ("1-1-ean-input", "1-2-retailer-config"):
        assert not [m for m in commits if m.endswith(f"story:{key}/dev r1")]
        assert [m for m in commits if m.endswith(f"story:{key}/dev r2")]


async def test_matrix_agent_reports_ambiguous_escalates_at_once(tmp_path):
    dr = make_dry_run(tmp_path, "agent-ambiguous")
    assert await run_dry(dr) == 2
    build = dr.unit("story:1-1-ean-input", "build")
    assert build.status is Status.ESCALATED
    assert build.round == 1
    assert dr.state.counter("story:1-1-ean-input", "tests") == 0
    assert "Which EAN lookup route" in (build.reason or "")
    assert not [r for r in dr.state.steps if r.scope == "story:1-1-ean-input" and r.step == "qa"]
    assert len(dr.ports.gh.issues) == 1


async def test_matrix_crash_and_resume(tmp_path):
    happy = make_dry_run(tmp_path / "happy")
    assert await run_dry(happy) == 0

    dr = make_dry_run(tmp_path / "crash", "crash")
    with pytest.raises(FakeCrash):
        await run_dry(dr)
    assert dr.orch._tasks == {}  # the loop cancelled and awaited the other steps
    assert dr.events()[-1]["type"] == "run_stopped"
    run_id = dr.state.run_id
    assert dr.orch.store is not None
    on_disk = dr.orch.store.load()
    crashed = [r for r in on_disk.steps if r.status is Status.RUNNING]
    assert any(r.scope == "story:1-2-retailer-config" and r.step == "qa" for r in crashed)
    head = on_disk.scope("story:1-2-retailer-config").head_sha

    # `sdlc resume <id>` in a new process: fresh fakes, default fixtures, state from disk.
    ports = fake_ports(FixtureBook(FIXTURES), str(dr.runs_dir))
    lines: list[str] = []
    orch = Orchestrator(
        load_pipeline(pipeline_text()), ports, dr.runs_dir, REPO, PROMPTS, echo=lines.append
    )
    assert await orch.resume(run_id) == 0
    worktree = on_disk.scope("story:1-2-retailer-config").worktree
    assert ("abort_rebase", worktree) in ports.git.calls
    assert ("reset_hard", worktree, head) in ports.git.calls
    qa = [r for r in orch.state.steps if r.scope == "story:1-2-retailer-config" and r.step == "qa"]
    assert [r.round for r in qa] == [1, 1]  # rerun in the same round
    assert qa[0].interrupted and qa[1].status is Status.PASSED
    assert statuses(orch.state) == statuses(happy.state)
    assert orch.state.budget.reserved == 0


def with_budget(text: str, usd: int) -> str:
    """The pipeline text with `run_budget_usd` set to `usd`, whatever the owner chose."""
    out, n = re.subn(r"(?m)^(\s*run_budget_usd:\s*)\S+", rf"\g<1>{usd}", text)
    assert n == 1
    return out


async def test_matrix_budget_cap(tmp_path):
    # AD-11 (amended): reservations alone only make a step wait. A $4 ceiling escalates once a
    # step's own cap ($5, architecture) cannot fit even with nothing running.
    text = with_budget(pipeline_text(), 4)
    pipeline = load_pipeline(text)
    dr = make_dry_run(tmp_path, pipeline=pipeline)
    assert await run_dry(dr) == 2
    assert dr.state.status is Status.ESCALATED
    assert dr.state.budget.exhausted
    assert dr.state.escalation("run") is not None
    # Replaying the audit: no step started past the cap (spent + reserved <= run_budget_usd).
    caps: dict[int, float] = {}
    spent = reserved = 0.0
    for e in dr.events():
        if e["type"] == "step_started":
            caps[e["seq"]] = e["cap"]
            reserved += e["cap"]
            assert spent + reserved <= 4 + 1e-9
        elif e["type"] == "step_finished":
            reserved -= caps[e["seq"]]
            spent += e["result"]["total_cost_usd"]
    assert not any(r.status is Status.RUNNING for r in dr.state.steps)


@pytest.mark.parametrize(
    ("needle", "replacement", "message"),
    [
        (
            "    steps:\n      - id: ux\n        kind: agent\n        skill: bmad-ux\n"
            '        agent_role: "Safta (UX Designer)"\n'
            "        prompt: ux\n        tools: [Skill, Read, Grep, Glob, Edit, Write]\n"
            "        inputs: [_bmad-output/planning-artifacts/prds/]\n        writes: true\n"
            "      - id: security_prd\n",
            "    steps:\n      - id: ux\n        kind: agent\n        skill: bmad-ux\n"
            '        agent_role: "Safta (UX Designer)"\n'
            "        prompt: ux\n        tools: [Skill, Read, Grep, Glob, Edit, Write]\n"
            "        inputs: [_bmad-output/planning-artifacts/prds/]\n        writes: true\n"
            "      - id: security_prd\n        writes: true\n",
            "stage 'design' step 'security_prd': two writers in one parallel group",
        ),
        (
            "        prompt: code-lens\n        tools: [Read, Grep, Glob]\n"
            "        inputs: [docs/standards/coding.md, docs/standards/testing.md]\n"
            "        verdict_source: result_block\n",
            "        prompt: code-lens\n        tools: [Read, Grep, Glob]\n"
            "        inputs: [docs/standards/coding.md, docs/standards/testing.md]\n"
            "        verdict_source: file\n",
            "stage 'build' step 'code_lens': verdict_source 'file' on a read-only step",
        ),
        ("    gate: judge_coverage\n", "    gate: vibes\n", "stage 'analysis': unknown gate kind"),
    ],
    ids=["two-writers", "file-verdict-read-only", "unknown-gate"],
)
def test_matrix_bad_pipeline(tmp_path, capsys, needle, replacement, message):
    text = pipeline_text()
    assert needle in text
    bad = tmp_path / "pipeline.yaml"
    # Replacing the first match keeps each case to one defect.
    bad.write_text(text.replace(needle, replacement, 1), encoding="utf-8")
    runs = tmp_path / "runs"
    code = cli.main(
        ["--repo", str(REPO), "--runs-dir", str(runs), "run", "--dry-run", "--pipeline", str(bad)]
    )
    assert code == 1
    assert message in capsys.readouterr().err
    assert not runs.exists()  # nothing ran


async def test_matrix_bad_story_block(tmp_path):
    dr = make_dry_run(tmp_path, "bad-story-block")
    assert await run_dry(dr) == 0
    breakdown = dr.unit("stage:breakdown", "breakdown")
    assert breakdown.round == 2
    assert dr.state.counter("stage:breakdown", "readiness") == 1
    assert dr.orch.store is not None
    gate = dr.orch.store.read_artifact("verdicts/stage:breakdown/r1/readiness.json") or ""
    assert '"fail"' in gate
    assert "story '1-1-ean-input': missing 'sources'" in gate
    assert "story '1-2-retailer-config': depends_on unknown story '9-9-missing-story'" in gate
    assert [s.key for s in dr.state.stories] == list(STORIES)
    r2 = next(
        inv
        for inv in dr.ports.claude.invocations
        if inv.key.scope == "stage:breakdown" and inv.key.step == "epics" and inv.key.round == 2
    )
    assert "story '1-1-ean-input': missing 'sources'" in r2.stdin
    assert "(AD-15)" in r2.stdin  # the gate's own blocker is not downgraded


async def test_uncited_blocker_is_downgraded_and_passes(tmp_path):
    dr = make_dry_run(tmp_path, "uncited-blocker")
    assert await run_dry(dr) == 0
    assert dr.unit("story:1-1-ean-input", "build").round == 1
    rec = next(
        r for r in dr.state.steps if r.scope == "story:1-1-ean-input" and r.step == "code_lens"
    )
    assert rec.verdict is not None and rec.verdict.verdict == "pass"
    assert rec.verdict.findings[0].severity == "major"


def test_ac1_cli_dry_run_exits_0_and_prints_argv(tmp_path, capsys):
    code = cli.main(["--repo", str(REPO), "--runs-dir", str(tmp_path / "runs"), "run", "--dry-run"])
    out = capsys.readouterr().out
    assert code == 0
    assert (
        "$ claude -p --output-format json --max-budget-usd 5.00 --no-session-persistence "
        "--allowedTools Read,Grep,Glob --tools Read,Grep,Glob --agent code-reviewer "
        "< code-lens.md"  # AD-7: stage model, no step override
    ) in out
    assert "status=passed" in out
    assert "--bare" not in out


def test_non_dry_run_stops_at_preflight(tmp_path, capsys, monkeypatch):
    """A real run with no tools on PATH exits 1 before its run dir exists (no paid call)."""
    empty = tmp_path / "empty-bin"
    empty.mkdir()
    monkeypatch.setenv("PATH", str(empty))
    code = cli.main(["--repo", str(REPO), "--runs-dir", str(tmp_path / "runs"), "run"])
    assert code == 1
    assert "preflight: claude not found on PATH" in capsys.readouterr().err
    assert not (tmp_path / "runs").exists()


def test_cli_resume_of_dry_run(tmp_path, capsys):
    runs = tmp_path / "runs"
    assert (
        cli.main(
            [
                "--repo",
                str(REPO),
                "--runs-dir",
                str(runs),
                "run",
                "--dry-run",
                "--scenario",
                "gate-fails-max-rounds",
            ]
        )
        == 2
    )
    run_id = next(p.name for p in runs.iterdir() if p.is_dir())
    # Resume reloads the run's scenario: the blocker is still there, so the scope gets
    # max_rounds fresh rounds (its counter was reset) and escalates again.
    assert cli.main(["--repo", str(REPO), "--runs-dir", str(runs), "resume", run_id]) == 2
    out = capsys.readouterr().out
    assert "story:1-1-ean-input          build          escalated      6" in out


def test_cli_resume_refuses_a_renamed_stage(tmp_path, capsys):
    runs = tmp_path / "runs"
    pipe = tmp_path / "pipeline.yaml"
    pipe.write_text(pipeline_text(), encoding="utf-8")
    base = ["--repo", str(REPO), "--runs-dir", str(runs)]
    assert cli.main([*base, "run", "--dry-run", "--pipeline", str(pipe), "--scenario",
                     "gate-fails-max-rounds"]) == 2  # fmt: skip
    run_id = next(p.name for p in runs.iterdir() if p.is_dir())
    pipe.write_text(pipeline_text().replace("  - id: infra\n", "  - id: infra2\n"), "utf-8")
    capsys.readouterr()
    assert cli.main([*base, "resume", run_id]) == 1
    assert "stage table" in capsys.readouterr().err


def test_cli_resume_refuses_an_exhausted_budget(tmp_path, capsys):
    runs = tmp_path / "runs"
    pipe = tmp_path / "pipeline.yaml"
    pipe.write_text(with_budget(pipeline_text(), 4), "utf-8")
    base = ["--repo", str(REPO), "--runs-dir", str(runs)]
    assert cli.main([*base, "run", "--dry-run", "--pipeline", str(pipe)]) == 2
    run_id = next(p.name for p in runs.iterdir() if p.is_dir())
    capsys.readouterr()
    assert cli.main([*base, "resume", run_id]) == 2
    err = capsys.readouterr().err
    assert "run budget exhausted" in err
    assert f"raise run_budget_usd in sdlc/pipeline.yaml, then sdlc resume {run_id}" in err


def test_cli_resume_after_raising_the_budget_proceeds(tmp_path, capsys):
    runs = tmp_path / "runs"
    pipe = tmp_path / "pipeline.yaml"
    pipe.write_text(with_budget(pipeline_text(), 4), "utf-8")
    base = ["--repo", str(REPO), "--runs-dir", str(runs)]
    assert cli.main([*base, "run", "--dry-run", "--pipeline", str(pipe)]) == 2
    run_id = next(p.name for p in runs.iterdir() if p.is_dir())
    # The owner raises the ceiling and a per-stage limit; the stage table is unchanged.
    raised = with_budget(pipeline_text(), 100)
    raised = raised.replace("    timeout_min: 60\n", "    timeout_min: 90\n")
    pipe.write_text(raised, "utf-8")
    capsys.readouterr()
    assert cli.main([*base, "resume", run_id]) == 0
    out = capsys.readouterr().out
    assert "status=passed" in out and "of $100.00" in out


def test_cli_resume_of_a_passed_run_is_a_clear_error(tmp_path, capsys):
    runs = tmp_path / "runs"
    base = ["--repo", str(REPO), "--runs-dir", str(runs)]
    assert cli.main([*base, "run", "--dry-run"]) == 0
    run_id = next(p.name for p in runs.iterdir() if p.is_dir())
    capsys.readouterr()
    assert cli.main([*base, "resume", run_id]) == 1
    err = capsys.readouterr().err
    assert "already passed" in err and "internal error" not in err


async def test_matrix_ci_red_rewinds_build_then_escalates(tmp_path):
    dr = make_dry_run(tmp_path, "ci-red")
    assert await run_dry(dr) == 2
    scope = "story:1-1-ean-input"
    assert dr.unit(scope, "build").round == 2  # one Developer fix round (AD-15)
    assert dr.unit(scope, "build").invalidated_by is None
    integrate = dr.unit(scope, "integrate")
    assert integrate.status is Status.ESCALATED and integrate.escalated_gate == "ci_green"
    assert dr.state.counter(scope, "ci_green") == 2
    assert dr.unit("story:1-3-compare-page", "build").status is Status.BLOCKED
    assert dr.unit("story:1-2-retailer-config", "integrate").status is Status.PASSED
    assert len(dr.ports.gh.issues) == 1


async def test_failed_rebase_is_aborted(tmp_path):
    from sdlc.adapters.fake import FixtureBook

    book = FixtureBook(FIXTURES, {"steps": {"story:1-1-ean-input/rebase/r1": {"exit_code": 1}}})
    dr = make_dry_run(tmp_path)
    ports = fake_ports(book, str(dr.runs_dir))
    pipeline = load_pipeline(pipeline_text())
    orch = Orchestrator(pipeline, ports, dr.runs_dir, REPO, PROMPTS, echo=lambda _: None)
    assert await orch.start(dry_run=True, fixtures=str(FIXTURES)) == 0
    wt = orch.state.scope("story:1-1-ean-input").worktree
    rebases = [i for i, c in enumerate(ports.git.calls) if c[:2] == ("rebase", wt)]
    assert ports.git.calls[rebases[0] + 1] == ("abort_rebase", wt)
    assert orch.state.unit("story:1-1-ean-input", "build").round == 2


async def test_reviewer_edits_outside_sdlc_fail_and_are_reset(tmp_path):
    dr = make_dry_run(tmp_path, "reviewer-edits")
    assert await run_dry(dr) == 0
    scope = "story:1-1-ean-input"
    rec = next(r for r in dr.state.steps if r.scope == scope and r.step == "code_review")
    assert rec.round == 1 and rec.outcome == "error"
    assert "backend/src/main/java/Hack.java" in (rec.reason or "")
    assert dr.unit(scope, "build").round == 2
    assert not [c for c in dr.ports.git.calls if c[0] == "commit" and "/code_review " in c[2]]


async def test_from_build_loads_stories_from_epics(tmp_path):
    dr = make_dry_run(tmp_path)
    book = FixtureBook(FIXTURES)
    code = await dr.orch.start(
        dry_run=True, from_stage="build", fixtures=str(FIXTURES), stories_reader=book.written_file
    )
    assert code == 0
    for stage in ("analysis", "requirements", "design", "architecture", "breakdown"):
        assert dr.unit(f"stage:{stage}", stage).status is Status.SKIPPED
    for key in STORIES:
        assert dr.unit(f"story:{key}", "build").status is Status.PASSED
    assert not [i for i in dr.ports.claude.invocations if i.key.scope.startswith("stage:")]


async def test_from_build_with_a_missing_stories_file_creates_nothing(tmp_path):
    from sdlc.app.loop import LoopError

    dr = make_dry_run(tmp_path)
    with pytest.raises(LoopError, match="not found"):
        await dr.orch.start(dry_run=True, from_stage="build", stories_reader=lambda rel: None)
    assert not dr.runs_dir.exists()


async def test_from_a_per_story_stage_with_no_stories_source_raises(tmp_path):
    from sdlc.app.loop import LoopError

    text = pipeline_text().replace(
        "    stories_file: _bmad-output/planning-artifacts/epics.md\n", ""
    )
    dr = make_dry_run(tmp_path, pipeline=load_pipeline(text))
    with pytest.raises(LoopError, match="no stories source"):
        await dr.orch.start(dry_run=True, from_stage="build")


async def test_run_ids_in_the_same_second_get_a_suffix(tmp_path):
    first = make_dry_run(tmp_path)
    second = make_dry_run(tmp_path)  # same runs dir, fresh fake clock: the same second
    await run_dry(first)
    await run_dry(second)
    assert second.state.run_id == first.state.run_id + "-2"
