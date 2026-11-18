"""Issue #87: the CI log excerpt fed to the Developer fix round (AD-15)."""

from __future__ import annotations

from sdlc.core.ci_log import CLIP_MARKER, LIMIT, excerpt

P = "backend\tRun check\t2026-11-18T10:00:00.1234567Z "  # `gh run view --log-failed` prefix


def _log(*lines: str) -> str:
    return "\n".join(P + line for line in lines)


def test_keeps_compiler_errors_and_annotations_without_prefix():
    out = excerpt(
        _log(
            "> Task :compileJava",
            "/w/backend/src/main/java/A.java:12: error: cannot find symbol",
            "##[error]Process completed with exit code 1.",
            "::error::web check failed",
        )
    )
    assert out.splitlines() == [
        "/w/backend/src/main/java/A.java:12: error: cannot find symbol",
        "##[error]Process completed with exit code 1.",
        "::error::web check failed",
    ]


def test_failing_test_keeps_its_first_cause_only():
    out = excerpt(
        _log(
            "LookupServiceTest > cachesVerdict() FAILED",
            "    org.opentest4j.AssertionFailedError: expected: <1> but was: <2>",
            "        at org.junit.Assert.fail(Assert.java:89)",
            "    Caused by: java.lang.IllegalStateException",
        )
    )
    assert "cachesVerdict() FAILED" in out
    assert "AssertionFailedError: expected: <1> but was: <2>" in out
    assert "Caused by" not in out and "Assert.java" not in out


def test_keeps_formatter_and_linter_file_lists():
    out = excerpt(
        _log(
            "[warn] Code style issues found in 2 files. Run Prettier with --write to fix.",
            "src/App.tsx",
            "  12:5  error  'x' is unused  @typescript-eslint/no-unused-vars",
            "The following files had format violations:",
            "    backend/src/main/java/ro/pricechecker/A.java",
        )
    )
    for want in ("Code style issues", "src/App.tsx", "12:5  error", "violations", "A.java"):
        assert want in out


def test_drops_git_credential_and_env_lines():
    out = excerpt(
        _log(
            "[command]/usr/bin/git fetch --depth=1 origin error: x",
            "git checkout --progress --force refs/remotes/pull/1/merge",
            "  GITHUB_TOKEN: ***",
            "  Authorization: bearer error: abc",
            "JAVA_HOME=/opt/hostedtoolcache/jdk error:",
            "env:",
            "src/A.java:1: error: boom",
        )
    )
    assert out == "src/A.java:1: error: boom"


def test_huge_log_is_clipped_with_marker():
    out = excerpt(_log(*(f"src/F{i}.java:1: error: e{i}" for i in range(500))))
    assert len(out) <= LIMIT and out.endswith(CLIP_MARKER)


def test_nothing_useful_is_empty():
    assert excerpt(_log("Downloading gradle", "BUILD SUCCESSFUL")) == ""
    assert excerpt("") == ""
