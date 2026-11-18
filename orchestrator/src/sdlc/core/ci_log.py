"""A short excerpt of a failed CI log for the Developer fix round (AD-15, issue #87).

`gh run view --log-failed` prints `<job>\t<step>\t<timestamp> <text>` per line. The excerpt
keeps the lines that say why the job failed (compiler errors, failing tests with their first
cause or assertion, formatter and linter file lists, `::error::` annotations) and drops git
output, credential and token lines and environment dumps. Pure: no I/O.
"""

from __future__ import annotations

import re

LIMIT = 4096
CLIP_MARKER = "\n[... excerpt clipped at 4096 chars]"

_PREFIX = re.compile(r"^[^\t]*\t[^\t]*\t")  # job and step columns of `--log-failed`
_STAMP = re.compile(r"^﻿?\d{4}-\d\d-\d\dT[\d:.]+Z ?")
_ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")

_DROP = re.compile(
    r"(?i)(token|secret|password|passwd|credential|authorization|bearer|api[_-]?key"
    r"|private[_-]?key|ghp_|gho_|ghs_|github_pat_)"
    r"|^\s*(git |\[command\]/usr/bin/git|From https?://|remote:|hint:|Switched to"
    r"|HEAD is now at|Fetching the repository|Syncing repository|Checking out the ref)"
    r"|^\s*(env:|export |##\[group\]Run |##\[endgroup\]|shell: )"
    r"|^\s*[A-Z][A-Z0-9_]{2,}=\S*"  # NAME=value env dump lines
)
_KEEP = re.compile(
    r"error:|\berror\b.*\[|::error::|##\[error\]"
    r"|\bFAILED\b|\bFAIL\b|\u2717|\u00d7|> Task .* FAILED|Tests? failed|test.*failed"
    r"|\[warn\]|Code style issues|spotless|Spotless|violations?\b|\d+ problems?"
    r"|^\s*\d+:\d+\s+error\s"  # ESLint `line:col  error  message  rule`
)
_FILE_LIST = re.compile(r"^\s*(\S+/)?\S+\.(java|kts?|tsx?|jsx?|css|py|ya?ml|json|md)\s*$")
_TEST_FAIL = re.compile(r"\bFAILED\b|\bFAIL\b|\u2717|\u00d7")
_CAUSE = re.compile(r"Caused by|AssertionError|AssertionFailedError|Expected|expected:|assert ")


def _clean(line: str) -> str:
    line = _ANSI.sub("", _PREFIX.sub("", line, count=1))
    return _STAMP.sub("", line).rstrip()


def excerpt(log: str, limit: int = LIMIT) -> str:
    """The useful lines of `log`, at most `limit` chars (a clip ends with a marker)."""
    kept: list[str] = []
    seen: set[str] = set()
    want_cause = False  # after a failing test, keep its first cause or assertion line
    for raw in log.splitlines():
        line = _clean(raw)
        if not line.strip() or _DROP.search(line):
            continue
        keep = bool(_KEEP.search(line) or _FILE_LIST.match(line))
        if want_cause and _CAUSE.search(line):
            want_cause, keep = False, True
        if _TEST_FAIL.search(line):
            want_cause = True
        if keep and line not in seen:
            seen.add(line)
            kept.append(line)
    text = "\n".join(kept)
    if len(text) <= limit:
        return text
    return text[: limit - len(CLIP_MARKER)].rstrip() + CLIP_MARKER
