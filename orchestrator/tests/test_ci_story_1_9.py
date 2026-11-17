"""Acceptance tests for story 1-9 (CI for backend and web, secret and vulnerability scans).

The orchestrator's `tests` gate runs only the commands in AGENTS.md (T-8), so these
tests read `.github/workflows/ci.yml` as YAML and run its shell steps locally with
bash. Stub `gitleaks` and `trivy` executables stand in for the scanners, so the
tests are offline and deterministic (T-3, T-5): they prove the step logic (canary
exit-code checks, commit-range selection, the aggregate `ci` verdict), not the
scanners' rule sets. The real scanners run on GitHub Actions on every PR.
Test names carry the acceptance criterion IDs (T-1).
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
BASH = shutil.which("bash")
GIT = shutil.which("git")

needs_bash = pytest.mark.skipif(BASH is None, reason="bash not on PATH")
needs_git = pytest.mark.skipif(GIT is None, reason="git not on PATH")

SCAN_JOBS = ("orchestrator", "backend", "web", "secret-scan", "vulnerability-scan")
SHA_PIN = re.compile(r"^[\w.-]+/[\w.-]+@[0-9a-f]{40}$")


def _workflow() -> dict[str, Any]:
    data = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    assert isinstance(data, dict)
    return data


def _jobs() -> dict[str, Any]:
    return _workflow()["jobs"]


def _triggers() -> dict[str, Any]:
    wf = _workflow()
    # PyYAML (YAML 1.1) reads the bare key `on` as the boolean True.
    return wf.get("on", wf.get(True))


def _step(job: str, name_prefix: str) -> dict[str, Any]:
    steps = [s for s in _jobs()[job]["steps"] if str(s.get("name", "")).startswith(name_prefix)]
    assert len(steps) == 1, f"expected one step in {job!r} named {name_prefix!r}*, got {steps}"
    return steps[0]


def _runs(job: str) -> list[str]:
    return [s["run"] for s in _jobs()[job]["steps"] if "run" in s]


def _stub_bin(tmp_path: Path, name: str, rc: int) -> tuple[Path, Path]:
    """Writes an executable `name` that logs its argv to a file and exits with `rc`."""
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    log = tmp_path / f"{name}.args"
    stub = bin_dir / name
    stub.write_text(f'#!/bin/sh\nprintf "%s\\n" "$*" >> "{log}"\nexit {rc}\n', encoding="utf-8")
    stub.chmod(0o755)
    return bin_dir, log


def _run_step(
    script: str,
    tmp_path: Path,
    *,
    path_prefix: Path | None = None,
    env: dict[str, str] | None = None,
    cwd: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    """Runs a workflow `run:` script the way GitHub runs it (bash -eo pipefail)."""
    script_file = tmp_path / "step.sh"
    script_file.write_text(script, encoding="utf-8")
    full_env = {
        "PATH": os.environ.get("PATH", ""),
        "HOME": str(tmp_path),
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_CONFIG_NOSYSTEM": "1",
        "TMPDIR": str(tmp_path),
        "RUNNER_TEMP": str(tmp_path),
        **(env or {}),
    }
    if path_prefix is not None:
        full_env["PATH"] = f"{path_prefix}{os.pathsep}{full_env['PATH']}"
    return subprocess.run(
        [str(BASH), "--noprofile", "--norc", "-eo", "pipefail", str(script_file)],
        cwd=cwd or tmp_path,
        env=full_env,
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
    )


# --- 1.9.1 ---------------------------------------------------------------


def test_ac_1_9_1_runs_on_every_pull_request_and_push_to_main() -> None:
    triggers = _triggers()
    assert "pull_request" in triggers
    assert triggers["push"]["branches"] == ["main"]


def test_ac_1_9_1_orchestrator_backend_and_web_jobs_exist() -> None:
    jobs = _jobs()
    for job in SCAN_JOBS:
        assert job in jobs, f"job {job!r} missing from ci.yml"


def test_ac_1_9_1_orchestrator_job_keeps_guard_and_orchestrator_checks() -> None:
    runs = "\n".join(_runs("orchestrator"))
    assert "python3 .claude/hooks/test_guard.py" in runs
    assert "ruff check" in runs and "ruff format --check" in runs
    assert "pytest -q" in runs


def test_ac_1_9_1_backend_job_runs_adr_0014_check_on_java_21() -> None:
    runs = _runs("backend")
    assert "backend/gradlew -p backend check" in runs
    setup = [
        s
        for s in _jobs()["backend"]["steps"]
        if str(s.get("uses", "")).startswith("actions/setup-java@")
    ]
    assert len(setup) == 1 and str(setup[0]["with"]["java-version"]) == "21"


def test_ac_1_9_1_web_job_runs_adr_0014_install_check_and_build_in_order() -> None:
    runs = _runs("web")
    expected = ["npm --prefix web ci", "npm --prefix web run check", "npm --prefix web run build"]
    assert [r.strip() for r in runs] == expected
    setup = [
        s
        for s in _jobs()["web"]["steps"]
        if str(s.get("uses", "")).startswith("actions/setup-node@")
    ]
    assert len(setup) == 1 and setup[0]["with"]["node-version-file"] == "web/.nvmrc"


def test_ac_1_9_1_gate_commands_match_agents_md_sdlc_commands() -> None:
    agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    block = agents.split("```yaml sdlc-commands", 1)[1].split("```", 1)[0]
    commands = yaml.safe_load(block)["tests"]
    assert "backend/gradlew -p backend check" in commands
    assert "backend/gradlew -p backend check" in _runs("backend")


def test_ac_1_9_1_aggregate_ci_needs_every_other_job_and_runs_unless_cancelled() -> None:
    jobs = _jobs()
    ci = jobs["ci"]
    assert set(ci["needs"]) == set(jobs) - {"ci"}, "ci must aggregate every other job"
    assert set(ci["needs"]) == set(SCAN_JOBS)
    assert ci["if"].replace(" ", "") == "${{!cancelled()}}", (
        "ci must run even when a needed job fails"
    )


def test_ac_1_9_1_aggregate_ci_treats_failure_cancelled_and_skipped_as_failure() -> None:
    env = _step("ci", "All jobs succeeded")["env"]["NOT_ALL_SUCCESS"]
    for result in ("failure", "cancelled", "skipped"):
        assert f"contains(needs.*.result, '{result}')" in env, f"ci ignores needs result {result!r}"


@needs_bash
@pytest.mark.parametrize(
    ("not_all_success", "expected_rc"),
    [("false", 0), ("true", 1), ("", 1)],
    ids=["all-success-passes", "any-bad-result-fails", "empty-expression-fails-closed"],
)
def test_ac_1_9_1_aggregate_ci_step_exit_code(
    tmp_path: Path, not_all_success: str, expected_rc: int
) -> None:
    script = _step("ci", "All jobs succeeded")["run"]
    proc = _run_step(script, tmp_path, env={"NOT_ALL_SUCCESS": not_all_success, "RESULTS": "x"})
    assert proc.returncode == expected_rc, proc.stdout + proc.stderr


def test_ac_1_9_1_no_retailer_contact_and_downloads_only_from_github_releases() -> None:
    # T-3: the only network fetches are the pinned scanner releases on github.com.
    text = WORKFLOW.read_text(encoding="utf-8")
    hosts = set(re.findall(r"https?://([^/\s\"']+)", text))
    assert hosts <= {"github.com", "registry.npmjs.org"}, hosts
    # registry.npmjs.org appears only as the canary lockfile's `resolved` field (never fetched).
    assert text.count("registry.npmjs.org") == 1
    assert "secrets." not in text, "CI uses no secrets (S-10)"


def test_ac_1_9_1_workflow_is_read_only_and_actions_are_sha_pinned() -> None:
    wf = _workflow()
    assert wf["permissions"] == {"contents": "read"}
    for name, job in wf["jobs"].items():
        assert "permissions" not in job, f"job {name!r} widens permissions"
        for step in job.get("steps", []):
            if "uses" in step:
                assert SHA_PIN.match(step["uses"]), (
                    f"{name}: {step['uses']} is not pinned to a commit SHA (S-12)"
                )


# --- 1.9.2 ---------------------------------------------------------------


def test_ac_1_9_2_gitleaks_is_pinned_by_version_and_sha256() -> None:
    env = _workflow()["env"]
    assert re.fullmatch(r"\d+\.\d+\.\d+", env["GITLEAKS_VERSION"])
    assert re.fullmatch(r"[0-9a-f]{64}", env["GITLEAKS_SHA256"])
    install = _step("secret-scan", "Install gitleaks")["run"]
    assert "sha256sum -c" in install


def test_ac_1_9_2_secret_scan_fetches_full_history_and_canary_runs_before_scan() -> None:
    steps = _jobs()["secret-scan"]["steps"]
    assert steps[0]["with"]["fetch-depth"] == 0
    names = [str(s.get("name", "")) for s in steps]
    canary = next(i for i, n in enumerate(names) if n.startswith("Canary"))
    scan = next(i for i, n in enumerate(names) if n.startswith("Scan the commit range"))
    assert canary < scan


def test_ac_1_9_2_secret_scan_has_no_allowlist_or_baseline() -> None:
    text = WORKFLOW.read_text(encoding="utf-8")
    assert "--baseline-path" not in text and "--config" not in text
    assert not (ROOT / ".gitleaks.toml").exists()
    assert not (ROOT / ".gitleaksignore").exists()


def test_ac_1_9_2_canary_key_is_not_a_literal_in_the_workflow() -> None:
    # The planted key is assembled at runtime; a literal AKIA key would trip the real scan.
    text = WORKFLOW.read_text(encoding="utf-8")
    assert not re.search(r"AKIA[0-9A-Z]{16}", text)


@needs_bash
@needs_git
@pytest.mark.parametrize(
    ("scanner_rc", "step_passes"),
    [(3, True), (1, False), (0, False), (126, False)],
    ids=["finding-detected", "fatal-error-exit-1", "nothing-found", "broken-binary"],
)
def test_ac_1_9_2_canary_fails_the_job_unless_gitleaks_reports_the_planted_key(
    tmp_path: Path, scanner_rc: int, step_passes: bool
) -> None:
    bin_dir, log = _stub_bin(tmp_path, "gitleaks", scanner_rc)
    proc = _run_step(_step("secret-scan", "Canary")["run"], tmp_path, path_prefix=bin_dir)
    assert (proc.returncode == 0) is step_passes, proc.stdout + proc.stderr
    args = log.read_text(encoding="utf-8")
    assert args.startswith("git ") and "--exit-code 3" in args and "--redact" in args


@needs_bash
@needs_git
def test_ac_1_9_2_canary_repo_holds_key_in_history_but_not_in_tree(tmp_path: Path) -> None:
    # Replace gitleaks with a probe that inspects the canary repo it was given.
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    report = tmp_path / "probe.txt"
    probe = bin_dir / "gitleaks"
    probe.write_text(
        "#!/bin/sh\n"
        'for a; do repo="$a"; done\n'
        f'git -C "$repo" log -p --format=%s > "{report}"\n'
        f'ls "$repo" > "{report}.ls"\n'
        "exit 3\n",
        encoding="utf-8",
    )
    probe.chmod(0o755)
    proc = _run_step(_step("secret-scan", "Canary")["run"], tmp_path, path_prefix=bin_dir)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    seen = report.read_text(encoding="utf-8")
    assert re.search(r"^\+aws_access_key_id = AKIA[0-9A-Z]{16}$", seen, re.MULTILINE), (
        "key never committed"
    )
    assert re.search(r"^-aws_access_key_id = AKIA", seen, re.MULTILINE), "key never removed"
    tree = Path(f"{report}.ls").read_text(encoding="utf-8")
    assert "config.ini" not in tree, "key file still in the working tree"


def _git(repo: Path, *args: str) -> str:
    env = {
        **os.environ,
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_AUTHOR_NAME": "t",
        "GIT_AUTHOR_EMAIL": "t@example.invalid",
        "GIT_COMMITTER_NAME": "t",
        "GIT_COMMITTER_EMAIL": "t@example.invalid",
    }
    out = subprocess.run(
        [str(GIT), "-C", str(repo), *args], env=env, capture_output=True, text=True, check=True
    )
    return out.stdout.strip()


@pytest.fixture
def two_commit_repo(tmp_path: Path) -> tuple[Path, str, str]:
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init", "-q")
    (repo / "a.txt").write_text("a\n", encoding="utf-8")
    _git(repo, "add", "a.txt")
    _git(repo, "commit", "-qm", "one")
    first = _git(repo, "rev-parse", "HEAD")
    (repo / "b.txt").write_text("b\n", encoding="utf-8")
    _git(repo, "add", "b.txt")
    _git(repo, "commit", "-qm", "two")
    return repo, first, _git(repo, "rev-parse", "HEAD")


@needs_bash
@needs_git
@pytest.mark.parametrize(
    ("event", "before", "expected_log_opts"),
    [
        ("pull_request", None, "--log-opts={first}..{second}"),
        ("push", "{first}", "--log-opts={first}..{second}"),
        ("push", "", "--log-opts=-1 {second}"),
        ("push", "0000000000000000000000000000000000000000", "--log-opts=-1 {second}"),
        ("push", "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef", "--log-opts=-1 {second}"),
    ],
    ids=[
        "pr-base-to-head",
        "push-before-to-sha",
        "push-empty-before",
        "push-new-ref",
        "push-after-force-push",
    ],
)
def test_ac_1_9_2_secret_scan_covers_the_events_commit_range(
    tmp_path: Path,
    two_commit_repo: tuple[Path, str, str],
    event: str,
    before: str | None,
    expected_log_opts: str,
) -> None:
    repo, first, second = two_commit_repo
    bin_dir, log = _stub_bin(tmp_path, "gitleaks", 0)
    env = {
        "EVENT_NAME": event,
        "PR_BASE_SHA": first if event == "pull_request" else "",
        "PR_HEAD_SHA": second if event == "pull_request" else "",
        "PUSH_BEFORE": (before or "").format(first=first),
        "PUSH_SHA": second,
    }
    proc = _run_step(
        _step("secret-scan", "Scan the commit range")["run"],
        tmp_path,
        path_prefix=bin_dir,
        env=env,
        cwd=repo,
    )
    assert proc.returncode == 0, proc.stdout + proc.stderr
    args = log.read_text(encoding="utf-8").strip()
    assert args.startswith("git ") and "--redact" in args
    assert expected_log_opts.format(first=first, second=second) in args


@needs_bash
@needs_git
def test_ac_1_9_2_secret_scan_fails_the_job_when_gitleaks_finds_a_secret(
    tmp_path: Path, two_commit_repo: tuple[Path, str, str]
) -> None:
    repo, first, second = two_commit_repo
    bin_dir, _ = _stub_bin(tmp_path, "gitleaks", 1)
    env = {
        "EVENT_NAME": "pull_request",
        "PR_BASE_SHA": first,
        "PR_HEAD_SHA": second,
        "PUSH_BEFORE": "",
        "PUSH_SHA": second,
    }
    proc = _run_step(
        _step("secret-scan", "Scan the commit range")["run"],
        tmp_path,
        path_prefix=bin_dir,
        env=env,
        cwd=repo,
    )
    assert proc.returncode != 0


def test_ac_1_9_2_main_pushes_are_never_cancelled_before_their_secret_scan() -> None:
    concurrency = _workflow()["concurrency"]
    assert (
        concurrency["cancel-in-progress"].replace(" ", "")
        == "${{github.event_name=='pull_request'}}"
    )


# --- 1.9.3 ---------------------------------------------------------------


def test_ac_1_9_3_trivy_is_pinned_by_version_and_sha256() -> None:
    env = _workflow()["env"]
    assert re.fullmatch(r"\d+\.\d+\.\d+", env["TRIVY_VERSION"])
    assert re.fullmatch(r"[0-9a-f]{64}", env["TRIVY_SHA256"])
    assert "sha256sum -c" in _step("vulnerability-scan", "Install Trivy")["run"]


def test_ac_1_9_3_scans_lockfiles_and_boot_jar_at_high_and_critical_and_fail() -> None:
    lockfiles = _step("vulnerability-scan", "Scan lockfiles")["run"]
    jar = _step("vulnerability-scan", "Scan the backend boot jar")["run"]
    for cmd, kind, target in ((lockfiles, "fs", " ."), (jar, "rootfs", "backend/build/libs")):
        assert cmd.startswith(f"trivy {kind} ")
        assert "--scanners vuln" in cmd
        assert "--severity HIGH,CRITICAL" in cmd
        assert "--exit-code 1" in cmd
        assert cmd.rstrip().endswith(target)
    assert "--include-dev-deps" not in lockfiles  # runtime deps only (spec Design Notes)


def test_ac_1_9_3_boot_jar_is_built_before_it_is_scanned_and_canary_runs_first() -> None:
    names = [str(s.get("name", "")) for s in _jobs()["vulnerability-scan"]["steps"]]
    canary = next(i for i, n in enumerate(names) if n.startswith("Canary"))
    lockfiles = next(i for i, n in enumerate(names) if n.startswith("Scan lockfiles"))
    build = next(i for i, n in enumerate(names) if n.startswith("Build the backend boot jar"))
    jar = next(i for i, n in enumerate(names) if n.startswith("Scan the backend boot jar"))
    assert canary < lockfiles < jar and build < jar
    assert (
        "backend/gradlew -p backend bootJar"
        in _step("vulnerability-scan", "Build the backend boot jar")["run"]
    )


@needs_bash
@pytest.mark.parametrize(
    ("scanner_rc", "step_passes"),
    [(3, True), (1, False), (0, False)],
    ids=["advisory-detected", "fatal-error-exit-1", "nothing-found"],
)
def test_ac_1_9_3_canary_fails_the_job_unless_trivy_reports_lodash_4_17_15(
    tmp_path: Path, scanner_rc: int, step_passes: bool
) -> None:
    bin_dir, log = _stub_bin(tmp_path, "trivy", scanner_rc)
    proc = _run_step(_step("vulnerability-scan", "Canary")["run"], tmp_path, path_prefix=bin_dir)
    assert (proc.returncode == 0) is step_passes, proc.stdout + proc.stderr
    args = log.read_text(encoding="utf-8")
    assert args.startswith("fs ") and "--severity HIGH,CRITICAL" in args and "--exit-code 3" in args


@needs_bash
def test_ac_1_9_3_canary_lockfile_is_valid_and_pins_lodash_4_17_15(tmp_path: Path) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    copy = tmp_path / "seen-lock.json"
    probe = bin_dir / "trivy"
    probe.write_text(
        f'#!/bin/sh\nfor a; do d="$a"; done\ncp "$d/package-lock.json" "{copy}"\nexit 3\n',
        encoding="utf-8",
    )
    probe.chmod(0o755)
    proc = _run_step(_step("vulnerability-scan", "Canary")["run"], tmp_path, path_prefix=bin_dir)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    lock = json.loads(copy.read_text(encoding="utf-8"))
    assert lock["lockfileVersion"] == 3
    assert lock["packages"]["node_modules/lodash"]["version"] == "4.17.15"
    assert lock["packages"][""]["dependencies"] == {
        "lodash": "4.17.15"
    }  # a runtime, not dev, dependency


@needs_bash
@pytest.mark.parametrize("step_prefix", ["Scan lockfiles", "Scan the backend boot jar"])
def test_ac_1_9_3_real_scan_fails_the_job_when_trivy_reports_an_advisory(
    tmp_path: Path, step_prefix: str
) -> None:
    bin_dir, _ = _stub_bin(tmp_path, "trivy", 1)
    proc = _run_step(_step("vulnerability-scan", step_prefix)["run"], tmp_path, path_prefix=bin_dir)
    assert proc.returncode == 1
