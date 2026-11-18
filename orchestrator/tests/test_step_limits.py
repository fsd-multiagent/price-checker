"""Story 0-55: per-step model, effort and max_turns (AD-7). Edge cases beyond test_pipeline.py."""

from __future__ import annotations

import pytest
from conftest import REPO, make_dry_run, pipeline_text, run_dry

from sdlc import cli
from sdlc.core.models import Effort
from sdlc.core.pipeline import PipelineError, load_pipeline

# code-lens and security-story stay on the stage model: on sonnet/medium they often ended
# with an empty final message and no verdict block (result_block), failing the round.
CHEAP_PROMPTS = ("brief-coverage", "security-prd", "security-architecture")


def _with_build_stage_limits(text: str) -> str:
    limits = "    model: opus\n    effort: high\n    max_turns: 40\n"
    return text.replace("  - id: build\n", "  - id: build\n" + limits, 1)


async def test_ac1_step_model_overrides_a_stage_model_and_others_keep_it(tmp_path):
    dr = make_dry_run(tmp_path, pipeline=load_pipeline(_with_build_stage_limits(pipeline_text())))
    assert await run_dry(dr) == 0
    invocations = dr.ports.claude.invocations  # type: ignore[attr-defined]
    by_step = {i.key.step: i.argv for i in invocations}
    for step_id in ("code_lens", "security"):
        assert by_step[step_id][-6:] == ("--model", "opus", "--effort", "high",
                                         "--max-turns", "40"), step_id  # fmt: skip
    assert by_step["code_review"][-6:] == ("--model", "opus", "--effort", "medium",
                                           "--max-turns", "40")  # fmt: skip
    for step_id in ("dev", "qa"):
        assert by_step[step_id][-6:] == ("--model", "opus", "--effort", "high",
                                         "--max-turns", "40"), step_id  # fmt: skip


def test_ac1_step_values_override_settings_defaults_without_stage_values():
    text = pipeline_text().replace("default_model: null", "default_model: opus", 1)
    text = text.replace("default_effort: null", "default_effort: high", 1)
    p = load_pipeline(text)
    build = p.stage("build")
    assert build.limits_for(build.step("code_lens"), p.settings) == ("opus", Effort.HIGH, None)
    assert build.limits_for(build.step("code_review"), p.settings) == ("opus", Effort.MEDIUM, None)
    assert build.limits_for(build.step("dev"), p.settings) == ("opus", Effort.HIGH, None)


def test_ac2_ultracode_on_the_real_coverage_judge_names_stage_and_step():
    # The real step already has an effort; replace that one instead of adding a duplicate key.
    bad = pipeline_text()
    start = bad.index("        prompt: brief-coverage\n")
    end = bad.index("effort: medium", start)
    bad = bad[:end] + "effort: ultracode" + bad[end + len("effort: medium") :]
    with pytest.raises(PipelineError, match=r"stage 'analysis' step 'coverage': .*effort"):
        load_pipeline(bad)


@pytest.mark.parametrize("value", ["0", "-3"])
def test_ac2_non_positive_step_max_turns_names_stage_and_step(value):
    bad = pipeline_text().replace(
        "        prompt: code-lens\n", f"        prompt: code-lens\n        max_turns: {value}\n", 1
    )
    with pytest.raises(PipelineError, match=r"stage 'build' step 'code_lens': max_turns"):
        load_pipeline(bad)


def test_ac2_step_max_turns_of_one_loads():
    text = pipeline_text().replace(
        "        prompt: code-lens\n", "        prompt: code-lens\n        max_turns: 1\n", 1
    )
    build = load_pipeline(text).stage("build")
    assert build.step("code_lens").max_turns == 1


def test_ac3_resume_accepts_a_step_tuning_change(tmp_path, capsys):
    runs = tmp_path / "runs"
    pipe = tmp_path / "pipeline.yaml"
    pipe.write_text(pipeline_text(), encoding="utf-8")
    base = ["--repo", str(REPO), "--runs-dir", str(runs)]
    assert cli.main([*base, "run", "--dry-run", "--pipeline", str(pipe), "--scenario",
                     "gate-fails-max-rounds"]) == 2  # fmt: skip
    run_id = next(p.name for p in runs.iterdir() if p.is_dir())
    tuned = pipeline_text().replace(
        "        prompt: dev\n",
        "        prompt: dev\n        model: haiku\n        effort: low\n        max_turns: 9\n",
        1,
    )
    pipe.write_text(tuned, "utf-8")
    capsys.readouterr()
    assert cli.main([*base, "resume", run_id]) == 2  # the scenario still fails; not refused
    assert "stage table" not in capsys.readouterr().err


def test_ac3_a_non_limit_step_change_still_changes_the_digest():
    """Control: only model, effort and max_turns are left out, not the whole step."""
    base = cli.stage_table_digest(load_pipeline(pipeline_text()))
    changed = pipeline_text().replace("        prompt: code-lens\n", "        prompt: dev\n", 1)
    assert cli.stage_table_digest(load_pipeline(changed)) != base


def test_ac4_code_review_keeps_the_stage_model_and_dev_qa_have_no_overrides(pipeline):
    build = pipeline.stage("build")
    review = build.step("code_review")
    assert (review.model, review.effort, review.max_turns) == (None, Effort.MEDIUM, None)
    for step_id in ("dev", "qa"):
        assert build.limits_for(build.step(step_id), pipeline.settings) == (
            build.model or pipeline.settings.default_model,
            build.effort or pipeline.settings.default_effort,
            build.max_turns,
        ), step_id


def test_ac5_cli_dry_run_prints_step_overrides(tmp_path, capsys):
    runs = tmp_path / "runs"
    argv = ["--repo", str(REPO), "--runs-dir", str(runs), "run", "--dry-run"]
    assert cli.main(argv) == 0
    lines = [x for x in capsys.readouterr().out.splitlines() if "$ claude -p " in x]
    for prompt in CHEAP_PROMPTS:
        hits = [x for x in lines if x.endswith(f"< {prompt}.md")]
        assert hits, prompt
        assert all(x.endswith(f"--model sonnet --effort medium < {prompt}.md") for x in hits)
    review = [x for x in lines if x.endswith("< code-review.md")]
    assert review and all("--effort medium" in x and "--model" not in x for x in review)
    dev = [x for x in lines if x.endswith("< dev.md")]
    assert dev and all("--model" not in x and "--effort" not in x for x in dev)
