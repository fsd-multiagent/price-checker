"""core/pipeline.py: load-time checks of AD-7, AD-16, AD-17 and AD-19."""

from __future__ import annotations

import pytest
from conftest import make_dry_run, pipeline_text, run_dry

from sdlc import cli
from sdlc.core.models import Effort, OutcomeSource, RunKind
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


# --------------------------------------------------------------------------- step limits (AD-7)

STEP_LIMITS = """
settings: {run_budget_usd: 10, default_model: opus, default_effort: low,
           agents: {T: [s, x, worker, stub-worker, judge]}}
stages:
  - id: one
    entry: [full]
    budget_usd: 1
    effort: high
    max_turns: 30
    steps:
      - {id: a, kind: agent, agent_role: T, skill: s, prompt: p, tools: [Read], writes: true}
      - {id: b, kind: agent, agent_role: T, agent: x, prompt: p, tools: [Read],
         verdict_source: result_block, model: sonnet, effort: medium, max_turns: 10}
    gate: review_verdicts
"""


def test_ac1_step_limits_override_the_stage():
    p = load_pipeline(STEP_LIMITS)
    stage, settings = p.stage("one"), p.settings
    assert stage.limits_for(stage.step("b"), settings) == ("sonnet", Effort.MEDIUM, 10)
    assert stage.limits_for(stage.step("a"), settings) == ("opus", Effort.HIGH, 30)


async def test_ac1_argv_carries_step_then_stage_values(tmp_path):
    text = pipeline_text().replace(
        "  - id: analysis\n", "  - id: analysis\n    effort: high\n    max_turns: 20\n", 1
    )
    text = text.replace(
        "        prompt: brief-coverage\n",
        "        prompt: brief-coverage\n        max_turns: 5\n",
        1,
    )
    dr = make_dry_run(tmp_path, pipeline=load_pipeline(text))
    assert await run_dry(dr) == 0
    invocations = dr.ports.claude.invocations  # type: ignore[attr-defined]
    coverage = next(i for i in invocations if i.key.step == "coverage")
    assert coverage.argv[-6:] == ("--model", "sonnet", "--effort", "medium", "--max-turns", "5")
    brief = next(i for i in invocations if i.key.step == "brief")
    assert brief.argv[-4:] == ("--effort", "high", "--max-turns", "20")
    assert "--model" not in brief.argv


def test_ac2_bad_step_effort_names_stage_and_step():
    bad = STEP_LIMITS.replace("effort: medium", "effort: ultracode", 1)
    with pytest.raises(PipelineError, match=r"stage 'one' step 'b': .*effort"):
        load_pipeline(bad)


def test_ac2_step_limits_on_a_script_step_are_a_load_error():
    bad = STEP_LIMITS.replace(
        "    gate: review_verdicts",
        "      - {id: t, kind: script, script: tests, effort: low}\n    gate: review_verdicts",
        1,
    )
    with pytest.raises(PipelineError, match=r"stage 'one' step 't': model, effort"):
        load_pipeline(bad)


def test_ac2_bad_step_max_turns_names_stage_and_step():
    bad = STEP_LIMITS.replace("max_turns: 10", "max_turns: 0", 1)
    with pytest.raises(PipelineError, match=r"stage 'one' step 'b': max_turns must be greater"):
        load_pipeline(bad)


def test_ac3_step_tuning_keeps_the_digest():
    base = STEP_LIMITS.replace(", model: sonnet, effort: medium, max_turns: 10", "", 1)
    assert cli.stage_table_digest(load_pipeline(STEP_LIMITS)) == cli.stage_table_digest(
        load_pipeline(base)
    )
    # The real pipeline with every step override removed, or with another one added.
    real = pipeline_text()
    step_fields = ("        model:", "        effort:", "        max_turns:")
    untuned = "\n".join(x for x in real.splitlines() if not x.startswith(step_fields))
    retuned = real.replace(
        "        prompt: code-lens\n", "        prompt: code-lens\n        max_turns: 7\n"
    )
    digest = cli.stage_table_digest(load_pipeline(real))
    assert cli.stage_table_digest(load_pipeline(untuned)) == digest
    assert cli.stage_table_digest(load_pipeline(retuned)) == digest


def test_ac4_real_pipeline_step_tuning(pipeline):
    s = pipeline.settings
    cheap = {
        ("analysis", "coverage"),
        ("design", "security_prd"),
        ("architecture", "security_arch"),
    }
    for stage_id, step_id in cheap:
        stage = pipeline.stage(stage_id)
        limits = stage.limits_for(stage.step(step_id), s)
        assert (limits.model, limits.effort) == ("sonnet", Effort.MEDIUM), (stage_id, step_id)
    build = pipeline.stage("build")
    assert build.limits_for(build.step("code_review"), s).effort is Effort.MEDIUM
    for step_id in ("dev", "qa", "code_lens", "security"):
        step = build.step(step_id)
        assert (step.model, step.effort, step.max_turns) == (None, None, None), step_id


async def test_ac5_dry_run_shows_step_model_and_effort(tmp_path):
    dr = make_dry_run(tmp_path)
    assert await run_dry(dr) == 0
    invocations = dr.ports.claude.invocations  # type: ignore[attr-defined]
    for step_id in ("coverage", "security_prd", "security_arch"):
        inv = next(i for i in invocations if i.key.step == step_id)
        assert "--model sonnet --effort medium" in " ".join(inv.argv), step_id
    dev = next(i for i in invocations if i.key.step == "dev")
    assert "--model" not in dev.argv and "--effort" not in dev.argv
