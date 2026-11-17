"""adapters/claude.py (AD-8), adapters/git.py (AD-12), store.py (AD-4), prompts (AD-8, AD-9)."""

from __future__ import annotations

import pytest
from conftest import FIXTURES, PROMPTS

from sdlc.adapters.claude import build_argv, parse_result
from sdlc.adapters.git import commit_message, refuse_main
from sdlc.core.models import RunKind, RunStarted, StepKind, VerdictSource
from sdlc.core.prompts import PROMPT_FIELDS, PromptError, render_prompt
from sdlc.core.state import apply, initial_state
from sdlc.ports import AdapterError
from sdlc.store import RunStore, list_runs


def test_build_argv_exact_shape():
    argv = build_argv(
        budget_usd=2,
        allowed_tools=("Read", "Grep", "Glob"),
        restrict_tools=True,
        agent="code-reviewer",
        model="sonnet",
    )
    assert argv == (
        "claude", "-p", "--output-format", "json", "--max-budget-usd", "2.00",
        "--no-session-persistence", "--allowedTools", "Read,Grep,Glob",
        "--tools", "Read,Grep,Glob", "--agent", "code-reviewer", "--model", "sonnet",
    )  # fmt: skip
    writer = build_argv(budget_usd=5, allowed_tools=("Skill", "Edit"), restrict_tools=False)
    assert "--tools" not in writer and "--agent" not in writer
    assert "--bare" not in writer


@pytest.mark.parametrize("sneaky", ["--bare=1", "--dangerously-skip-permissions=true"])
def test_forbidden_flag_with_a_value_trips(sneaky):
    with pytest.raises(AdapterError, match="forbidden"):
        build_argv(budget_usd=1, allowed_tools=("Read",), restrict_tools=True, agent=sneaky)


def test_parse_result_fixtures():
    ok = parse_result((FIXTURES / "results" / "success.json").read_text(encoding="utf-8"))
    assert ok.subtype == "success" and ok.total_cost_usd == 0.12 and ok.num_turns == 7
    err = parse_result((FIXTURES / "results" / "error-max-budget.json").read_text(encoding="utf-8"))
    assert err.subtype == "error_max_budget_usd" and err.is_error
    assert err.errors == ("Reached maximum budget ($0.50)",)
    assert err.permission_denials[0].tool_name == "Bash"
    with pytest.raises(AdapterError):
        parse_result("not json")
    with pytest.raises(AdapterError):
        parse_result("{}")


def test_git_refuses_main():
    for ref in ("main", "refs/heads/main", "HEAD:main", "HEAD:refs/heads/main", "+main",
                "+refs/heads/main", "HEAD:+main", "+HEAD:main"):  # fmt: skip
        with pytest.raises(AdapterError, match="main"):
            refuse_main(ref)
    refuse_main("story/1-1-a")
    assert commit_message("r1", "story:1-1-a", "dev", 2) == "sdlc(r1): story:1-1-a/dev r2"


def test_store_is_atomic_and_round_trips(tmp_path, pipeline):
    store = RunStore(tmp_path / "runs" / "r1")
    store.create()
    s = initial_state(pipeline, run_id="r1", kind=RunKind.FULL, at="t", dry_run=True)
    store.save(s)
    event = RunStarted(at="t")
    s2 = apply(pipeline, s, event)
    store.append_event(event)
    store.save(s2)
    assert store.load() == s2
    assert store.events_path.read_text().count("\n") == 1
    assert not list(store.dir.glob(".state.*.tmp"))
    assert list_runs(tmp_path / "runs") == ["r1"]


def test_every_prompt_renders_and_starts_with_its_skill(pipeline):
    context = {f: f"<{f}>" for f in PROMPT_FIELDS}
    for stage in pipeline.stages:
        for step in stage.steps:
            if step.kind is StepKind.SCRIPT:
                continue
            text = (PROMPTS / f"{step.prompt}.md").read_text(encoding="utf-8")
            out = render_prompt(text, context)
            assert "_bmad-output/sdlc/" not in out  # AD-9
            assert "<feedback>" in out and "<inputs>" in out
            if step.skill:
                assert out.startswith(f"/{step.skill}"), step.id
            if step.verdict_source is VerdictSource.FILE:
                assert "<verdict_path>" in out, step.id
            else:  # a result_block verdict has no path to promise (AD-17)
                assert "<verdict_path>" not in out, step.id
            if step.verdict_source is VerdictSource.RESULT_BLOCK:
                assert "exactly one fenced `json` block" in out, step.id
    for name in ("dev", "security-story"):
        out = render_prompt((PROMPTS / f"{name}.md").read_text(encoding="utf-8"), context)
        assert "<story_sources>" in out and "<story_touches>" in out


def test_prompt_with_unknown_placeholder_fails():
    context = {f: "x" for f in PROMPT_FIELDS}
    with pytest.raises(PromptError):
        render_prompt("hello ${nope}", context)
    with pytest.raises(PromptError, match="lacks"):
        render_prompt("hello", {})
