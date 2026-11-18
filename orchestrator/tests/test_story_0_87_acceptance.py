"""Story 0-87 (issue #87) acceptance tests, one or more per criterion (T-1).

AC-1 integrate runs the tests gate after a clean rebase, before any PR.
AC-2 a red ci_green finding holds a <=4 KB excerpt of the failed jobs' logs, without noise.
AC-3 the next Developer fix round sees that text through ${feedback}.
AC-4 dry-run scenario `rebase-tests-red` catches the failure in integrate before open_pr.
AC-5 the spine (AD-15, AD-18) and AGENTS.md describe both.
"""

from __future__ import annotations

import json
import re

from conftest import FIXTURES, PROMPTS, REPO, make_dry_run, pipeline_text, run_dry

from sdlc import cli
from sdlc.adapters.fake import FixtureBook, fake_ports
from sdlc.app.loop import Orchestrator
from sdlc.core.ci_log import LIMIT, excerpt
from sdlc.core.models import Outcome, Status
from sdlc.core.pipeline import load_pipeline

SCOPE = "story:1-1-ean-input"
SPINE = (
    REPO
    / "_bmad-output/planning-artifacts/architecture/architecture-orchestrator-2026-11-16"
    / "ARCHITECTURE-SPINE.md"
)
P = "web\tcheck\t2026-11-18T10:00:00.1234567Z "  # `gh run view --log-failed` prefix
REBASED_ERROR = "RetryAfterAcceptanceGapsTest.java:216: error: constructor LookupService cannot"


def _orch(tmp_path, steps: dict) -> tuple[Orchestrator, object]:
    book = FixtureBook(FIXTURES, {"steps": steps})
    runs = tmp_path / "runs"
    pipeline = load_pipeline(pipeline_text())
    ports = fake_ports(book, str(runs), pipeline.settings)
    return Orchestrator(pipeline, ports, runs, REPO, PROMPTS, echo=lambda _: None), ports


def _records(orch: Orchestrator, stage: str, step: str | None = None) -> list:
    assert orch.state is not None
    return [
        r
        for r in orch.state.steps
        if r.scope == SCOPE and r.stage == stage and (step is None or r.step == step)
    ]


def _verdict(orch: Orchestrator, round_: int) -> dict:
    assert orch.store is not None
    text = orch.store.read_artifact(f"verdicts/{SCOPE}/r{round_}/ci_green.json")
    assert text is not None
    return json.loads(text)


def _summary(orch: Orchestrator, round_: int, step: str) -> str:
    return next(
        f["summary"] for f in _verdict(orch, round_)["findings"] if f["id"] == f"{step}-outcome"
    )


def _dev_prompt(ports, round_: int) -> str:
    return next(
        inv.stdin
        for inv in ports.claude.invocations
        if inv.key.scope == SCOPE and inv.key.step == "dev" and inv.key.round == round_
    )


def _red_after_rebase_in_round_1(ports) -> None:
    """The fake tests command fails with a compiler error only on round 1's tests_rebased."""
    real = ports.commands.run

    async def run(name, worktree, timeout_s, key, factory=False):
        if key.scope == SCOPE and key.step == "tests_rebased" and key.round == 1:
            ports.commands.calls.append((name, worktree))
            return 1, f"> Task :compileTestJava FAILED\n{REBASED_ERROR}"
        return await real(name, worktree, timeout_s, key, factory=factory)

    ports.commands.run = run


# --- AC-1 ------------------------------------------------------------------------------------


def test_ac1_integrate_runs_the_tests_script_between_rebase_and_open_pr():
    stage = load_pipeline(pipeline_text()).stage("integrate")
    ids = [s.id for s in stage.steps]
    assert ids.index("rebase") + 1 == ids.index("tests_rebased") < ids.index("open_pr")
    step = stage.step("tests_rebased")
    assert step.kind.value == "script" and step.script == "tests"  # same commands as build


async def test_ac1_red_tests_after_rebase_fail_the_round_with_output_and_no_pr(tmp_path):
    orch, ports = _orch(tmp_path, {})
    _red_after_rebase_in_round_1(ports)
    assert await orch.start(dry_run=True, fixtures=str(FIXTURES)) == 0

    r1 = {r.step: r for r in _records(orch, "integrate") if r.round == 1}
    assert r1["tests_rebased"].outcome is not Outcome.DONE
    assert r1["tests_rebased"].seq > r1["rebase"].seq
    assert "open_pr" not in r1 and "ci_green" not in r1  # no PR, no CI in the red round
    assert "tests_rebased" in {r.step for r in _records(orch, "integrate") if r.round == 2}
    assert ("tests", orch.state.scope(SCOPE).worktree) in ports.commands.calls

    summary = _summary(orch, 1, "tests_rebased")
    assert REBASED_ERROR in summary and "compileTestJava FAILED" in summary
    pr_calls = [c for c in ports.gh.calls if c[0] == "ensure_pr" and c[1] == "story/1-1-ean-input"]
    assert len(pr_calls) == 1  # opened only once, in the green round 2
    assert orch.state.unit(SCOPE, "integrate").status is Status.PASSED


async def test_ac1_red_tests_after_rebase_do_not_update_an_open_pr(tmp_path):
    """Round 1 opens the PR and CI is red; round 2's red post-rebase tests push nothing."""
    orch, ports = _orch(tmp_path, {f"{SCOPE}/ci_green/r1": {"exit_code": 1}})
    real = ports.commands.run

    async def run(name, worktree, timeout_s, key, factory=False):
        if key.scope == SCOPE and key.step == "tests_rebased" and key.round == 2:
            return 1, REBASED_ERROR
        return await real(name, worktree, timeout_s, key, factory=factory)

    ports.commands.run = run
    assert await orch.start(dry_run=True, fixtures=str(FIXTURES)) == 2

    r2 = {r.step for r in _records(orch, "integrate") if r.round == 2}
    assert "tests_rebased" in r2 and "open_pr" not in r2 and "ci_green" not in r2
    branch = "story/1-1-ean-input"
    pushes = [c for c in ports.git.calls if c[0] == "push" and c[1] == branch]
    prs = [c for c in ports.gh.calls if c[0] == "ensure_pr" and c[1] == branch]
    assert len(pushes) == 1 and len(prs) == 1  # only round 1's; the open PR is not updated
    assert REBASED_ERROR in _summary(orch, 2, "tests_rebased")
    assert orch.state.unit(SCOPE, "integrate").status is Status.ESCALATED


# --- AC-2------------------------------------------------------------------------------------


def _log(*lines: str) -> str:
    return "\n".join(P + line for line in lines)


def test_ac2_excerpt_keeps_compiler_errors_test_causes_and_formatter_lists():
    out = excerpt(
        _log(
            "> Task :compileTestJava",
            f"/w/backend/src/test/java/{REBASED_ERROR}",
            "E2eJourneysApiTest > lookup() FAILED",
            "    java.lang.IllegalStateException: Failed to load ApplicationContext",
            "        at org.springframework.test.Ctx.load(Ctx.java:1)",
            "    Caused by: java.lang.IllegalStateException at AdapterCatalog.java:38",
            "> Task :spotlessJavaCheck FAILED",
            "The following files had format violations:",
            "    backend/src/main/java/ro/pricechecker/lookup/LookupService.java",
            "[warn] Code style issues found in 1 file. Run Prettier with --write to fix.",
            "src/features/lookup/LookupPage.tsx",
            "::error::Process completed with exit code 1.",
        )
    )
    for want in (
        REBASED_ERROR,
        "lookup() FAILED",
        "Caused by: java.lang.IllegalStateException at AdapterCatalog.java:38",
        "spotlessJavaCheck FAILED",
        "lookup/LookupService.java",
        "Code style issues",
        "src/features/lookup/LookupPage.tsx",
        "::error::Process completed",
    ):
        assert want in out, want
    assert "Ctx.java" not in out  # stack frames are not kept
    assert "\t" not in out and "2026-11-18T" not in out  # job, step and time columns dropped


def test_ac2_excerpt_drops_git_credential_and_env_noise():
    secret_like = "gh" + "p_" + "a" * 36  # built from parts (secret-scan pitfall)
    out = excerpt(
        _log(
            "[command]/usr/bin/git -c http.extraheader=AUTHORIZATION: basic *** fetch",
            "From https://github.com/o/r",
            "remote: Enumerating objects: 5, done.",
            "HEAD is now at abc123 error: msg",
            f"token {secret_like} error: leaked",
            "  password: *** error:",
            "GITHUB_TOKEN=*** FAILED",
            "export NODE_ENV=production error:",
            "src/A.java:1: error: boom",
        )
    )
    assert out == "src/A.java:1: error: boom"


def test_ac2_excerpt_is_at_most_about_4_kb():
    out = excerpt(_log(*(f"src/F{i}.java:{i}: error: cannot find symbol" for i in range(1000))))
    assert len(out) <= LIMIT == 4096


async def test_ac2_ci_green_finding_holds_the_bounded_excerpt(tmp_path):
    big = _log(*(f"src/F{i}.java:1: error: e{i}" for i in range(1000)), "  GITHUB_TOKEN: ***")
    orch, _ = _orch(tmp_path, {f"{SCOPE}/ci_green/r1": {"exit_code": 1, "ci_log": big}})
    assert await orch.start(dry_run=True, fixtures=str(FIXTURES)) == 0
    summary = _summary(orch, 1, "ci_green")
    assert "src/F1.java:1: error: e1" in summary and "TOKEN" not in summary
    assert "excerpt clipped" in summary
    assert len(summary) <= LIMIT + 200  # the step header plus a 4 KB tail: about 4 KB


# --- AC-3 ------------------------------------------------------------------------------------


async def test_ac3_ci_excerpt_reaches_the_next_dev_prompt(tmp_path):
    log = _log("E2eJourneysApiTest > lookup() FAILED", "    Caused by: boom at AdapterCatalog:38")
    orch, ports = _orch(tmp_path, {f"{SCOPE}/ci_green/r1": {"exit_code": 1, "ci_log": log}})
    assert await orch.start(dry_run=True, fixtures=str(FIXTURES)) == 0
    assert "checks not green" in _summary(orch, 1, "ci_green")
    assert "AdapterCatalog" not in _dev_prompt(ports, 1)
    prompt = _dev_prompt(ports, 2)
    assert "E2eJourneysApiTest > lookup() FAILED" in prompt
    assert "Caused by: boom at AdapterCatalog:38" in prompt


async def test_ac3_post_rebase_test_output_reaches_the_next_dev_prompt(tmp_path):
    orch, ports = _orch(tmp_path, {})
    _red_after_rebase_in_round_1(ports)
    assert await orch.start(dry_run=True, fixtures=str(FIXTURES)) == 0
    assert REBASED_ERROR not in _dev_prompt(ports, 1)
    assert REBASED_ERROR in _dev_prompt(ports, 2)


# --- AC-4 ------------------------------------------------------------------------------------


async def test_ac4_scenario_build_green_then_caught_in_integrate_before_open_pr(tmp_path):
    dr = make_dry_run(tmp_path, "rebase-tests-red")
    assert await run_dry(dr) == 2
    build_tests = [r for r in _records(dr.orch, "build", "tests") if r.round == 1]
    assert build_tests and all(r.outcome is Outcome.DONE for r in build_tests)  # passes build
    integrate = _records(dr.orch, "integrate")
    assert {r.step for r in integrate} == {"rebase", "tests_rebased"}  # never reaches open_pr
    assert all(r.outcome is not Outcome.DONE for r in integrate if r.step == "tests_rebased")
    assert dr.unit(SCOPE, "integrate").status is Status.ESCALATED


def test_ac4_cli_dry_run_scenario_rebase_tests_red(tmp_path, capsys):
    runs = tmp_path / "runs"
    argv = ["--repo", str(REPO), "--runs-dir", str(runs), "run", "--dry-run"]
    assert cli.main([*argv, "--scenario", "rebase-tests-red"]) == 2
    out = capsys.readouterr().out
    assert "tests_rebased" in out
    assert re.search(r"story:1-1-ean-input\s+integrate\s+escalated", out)


# --- AC-5 ------------------------------------------------------------------------------------


def _section(text: str, heading: str) -> str:
    start = text.index(heading)
    end = text.find("\n### ", start + 1)
    return text[start : end if end != -1 else None]


def test_ac5_spine_ad15_and_ad18_describe_the_gate_and_the_excerpt():
    spine = SPINE.read_text(encoding="utf-8")
    ad15 = _section(spine, "### AD-15")
    assert "tests_rebased" in ad15 and "open_pr" in ad15
    assert "ci_log" in ad15 and "4096" in ad15 and "--log-failed" in ad15
    ad18 = _section(spine, "### AD-18")
    assert "${feedback}" in ad18 and "4096" in ad18


def test_ac5_agents_md_describes_the_gate_and_the_excerpt():
    agents = (REPO / "AGENTS.md").read_text(encoding="utf-8")
    line = next(x for x in agents.splitlines() if "issue #87" in x)
    for want in ("tests_rebased", "ci_green", "ci_log.py", "4 KB", "${feedback}"):
        assert want in line, want
    assert "rebase-tests-red" in line
