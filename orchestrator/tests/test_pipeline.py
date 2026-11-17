"""core/pipeline.py: load-time checks of AD-7, AD-16, AD-17 and AD-19."""

from __future__ import annotations

import pytest
from conftest import pipeline_text

from sdlc.core.models import OutcomeSource, RunKind
from sdlc.core.pipeline import PipelineError, load_pipeline

MINIMAL = """
settings: {run_budget_usd: 10, agents: {T: [s, x, worker, stub-worker, judge]}}
stages:
  - id: one
    entry: [full]
    budget_usd: 1
    steps:
{steps}
{extra}
"""

WRITER = (
    "      - {id: a, kind: agent, agent_role: T, skill: s, prompt: p, tools: [Read], writes: true}"
)


def load(
    steps: str = WRITER, extra: str = "    gate: review_verdicts", settings: str = ""
) -> object:
    text = MINIMAL.replace("{steps}", steps).replace("{extra}", extra)
    if settings:
        text = text.replace(
            "settings: {run_budget_usd: 10, agents: {T: [s, x, worker, stub-worker, judge]}}",
            settings,
        )
    return load_pipeline(text)


def test_real_pipeline_loads_with_defaults(pipeline):
    s = pipeline.settings
    assert (s.max_rounds, s.max_parallel, s.timeout_min, s.ci_timeout_min) == (3, 3, 30, 30)
    assert s.serial_paths == ("api/openapi.yaml",)
    assert "AGENTS.md" in s.bookkeeping_paths
    arch = pipeline.stage("architecture")
    assert arch.timeout_min == 60
    assert pipeline.stage("analysis").timeout_min == 30
    assert pipeline.stage("requirements").needs == ("analysis",)
    assert pipeline.stage("design").on_fail == "design"
    assert pipeline.stage("integrate").on_fail == "build"
    build = pipeline.stage("build")
    assert build.step("code_lens").outcome_source is OutcomeSource.VERDICT
    assert build.step("dev").outcome_source is OutcomeSource.RESULT_STATUS
    assert build.step("tests").outcome_source is OutcomeSource.EXIT_CODE
    assert [tuple(s.id for s in ph) for ph in build.phases()] == [
        ("dev",),
        ("qa",),
        ("tests",),
        ("code_review", "code_lens", "security"),
    ]
    assert RunKind.STORY in build.entry and RunKind.STORY not in arch.entry


def test_no_stage_name_is_hard_coded_in_python():
    import ast
    from pathlib import Path

    src = Path(__file__).resolve().parents[1] / "src" / "sdlc"
    # "learn" is left out: it is also a scope kind (`learn:<key>`), not only a stage id.
    names = {"analysis", "requirements", "design", "architecture", "breakdown", "planning-pr",
             "infra", "build", "integrate", "retro"}  # fmt: skip
    found = []
    for f in src.rglob("*.py"):
        for node in ast.walk(ast.parse(f.read_text(encoding="utf-8"))):
            if isinstance(node, ast.Constant) and node.value in names:
                found.append(f"{f.name}: {node.value!r}")
    assert found == []


def test_missing_run_budget_is_a_load_error():
    with pytest.raises(PipelineError, match="run_budget_usd"):
        load(settings="settings: {}")


def test_unknown_gate_kind_names_the_stage():
    with pytest.raises(PipelineError, match="stage 'one': unknown gate kind 'vibes'"):
        load(extra="    gate: vibes")


def test_unknown_step_kind_names_the_step():
    with pytest.raises(PipelineError, match="stage 'one' step 'a'"):
        load(steps="      - {id: a, kind: wizard, prompt: p}")


def test_two_writers_in_a_parallel_group():
    steps = (
        WRITER + "\n      - {id: b, kind: agent, agent_role: T, skill: s, prompt: p, tools: [Read],"
        " writes: true}"
    )
    with pytest.raises(PipelineError, match="stage 'one' step 'b': two writers"):
        load(steps=steps, extra="    parallel: [[a, b]]\n    gate: review_verdicts")


def test_one_writer_in_a_parallel_group_is_legal():
    steps = (
        WRITER + "\n      - {id: b, kind: judge, agent_role: T, prompt: p, tools: [Read],"
        " verdict_source: result_block}"
    )
    load(steps=steps, extra="    parallel: true\n    gate: review_verdicts")


def test_file_verdict_on_read_only_step():
    steps = (
        "      - {id: r, kind: agent, agent_role: T, agent: x, prompt: p, tools: [Read],"
        " verdict_source: file}"
    )
    with pytest.raises(PipelineError, match="stage 'one' step 'r': verdict_source 'file'"):
        load(steps=steps)


def test_judge_must_be_read_only_and_declare_a_verdict():
    with pytest.raises(PipelineError, match="judge is read-only"):
        load(
            steps="      - {id: j, kind: judge, agent_role: T, prompt: p, tools: [Read],"
            " writes: true, verdict_source: file}"
        )
    with pytest.raises(PipelineError, match="must declare a verdict_source"):
        load(steps="      - {id: j, kind: judge, agent_role: T, prompt: p, tools: [Read]}")


def test_unknown_script():
    with pytest.raises(PipelineError, match="stage 'one' step 'x': unknown script 'deploy'"):
        load(steps="      - {id: x, kind: script, script: deploy}")


def test_on_fail_must_be_same_or_earlier_stage():
    text = """
settings: {run_budget_usd: 10, agents: {T: [s, x, worker, stub-worker, judge]}}
stages:
  - id: one
    entry: [full]
    on_fail: two
    steps: [{id: x, kind: script, script: tests}]
    gate: tests
  - id: two
    entry: [full]
    steps: [{id: x, kind: script, script: tests}]
    gate: tests
"""
    with pytest.raises(PipelineError, match="stage 'one': on_fail 'two'"):
        load_pipeline(text)


def test_needs_must_name_an_earlier_stage():
    with pytest.raises(PipelineError, match="needs 'zero'"):
        load(extra="    needs: [zero]\n    gate: review_verdicts")


def test_gate_step_must_exist():
    with pytest.raises(PipelineError, match="gate 'tests' names an unknown step"):
        load(extra="    gate: {kind: tests, steps: [nope]}")


def test_parallel_group_must_be_contiguous():
    steps = (
        "      - {id: a, kind: script, script: tests}\n"
        "      - {id: b, kind: script, script: tests}\n"
        "      - {id: c, kind: script, script: tests}"
    )
    with pytest.raises(PipelineError, match="not contiguous"):
        load(steps=steps, extra="    parallel: [[a, c]]\n    gate: tests")


def test_real_pipeline_text_is_valid_yaml_mapping():
    assert "stages:" in pipeline_text()


# One test per load rule added after review; each names the stage, and the step where one applies.


def test_agent_stage_needs_a_positive_budget():
    with pytest.raises(PipelineError, match=r"stage 'one' step 'a': .*budget_usd > 0"):
        load_pipeline(
            MINIMAL.replace("    budget_usd: 1\n", "    budget_usd: 0\n")
            .replace("{steps}", WRITER)
            .replace("{extra}", "    gate: review_verdicts")
        )


def test_empty_gate_list():
    with pytest.raises(PipelineError, match="stage 'one': needs a gate"):
        load(extra="    gate: []")


def test_empty_parallel_group():
    with pytest.raises(PipelineError, match="stage 'one': empty parallel group"):
        load(extra="    parallel: [[]]\n    gate: review_verdicts")


def test_unknown_branch_placeholder():
    with pytest.raises(PipelineError, match=r"stage 'one': branch template .* story_id"):
        load(extra="    branch: 'x/{story_id}'\n    gate: review_verdicts")


@pytest.mark.parametrize("field", ["max_rounds", "timeout_min"])
def test_rounds_and_timeout_must_be_positive(field):
    with pytest.raises(PipelineError, match=f"stage 'one': {field} must be greater than 0"):
        load(extra=f"    {field}: 0\n    gate: review_verdicts")


def test_result_status_with_result_block_verdict():
    steps = (
        "      - {id: r, kind: agent, agent_role: T, agent: x, prompt: p, tools: [Read],"
        " verdict_source: result_block, outcome_source: result_status}"
    )
    with pytest.raises(PipelineError, match="stage 'one' step 'r': outcome_source 'result_status'"):
        load(steps=steps)


def test_agent_step_needs_tools():
    with pytest.raises(PipelineError, match="stage 'one' step 'a': an agent step needs a tools"):
        load(steps="      - {id: a, kind: agent, agent_role: T, skill: s, prompt: p}")
