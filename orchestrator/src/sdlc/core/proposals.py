"""AD-26: path tiers and owner-review proposals. Pure (AD-1).

A tier entry ending in `/` is a path prefix; any other entry is an exact file (or, written
without the slash, the directory of that name).
"""

from __future__ import annotations

import difflib
import hashlib
import re
from collections.abc import Iterable

from sdlc.core.models import Proposal, ProposalStatus, RunState, Settings

OWNER_REVIEW_LABEL = "owner-review"
BODY_LIMIT = 65_000  # GitHub caps a PR or issue body at 65 536 characters
LISTED_PATHS = 50  # paths named in a body; the rest are counted


def in_tier(path: str, entries: Iterable[str]) -> bool:
    for entry in entries:
        if entry.endswith("/"):
            if path.startswith(entry):
                return True
        elif path == entry or path.startswith(entry + "/"):
            return True
    return False


def tier_paths(paths: Iterable[str], entries: Iterable[str]) -> list[str]:
    entries = tuple(entries)
    return [p for p in paths if in_tier(p, entries)]


def change_key(entries: Iterable[tuple[str, str | None]]) -> str:
    """The SHA-256 of a normalised change: each path with its new content id (or deletion),
    sorted. The same edit cut from different bases gives the same key."""
    lines = sorted(f"{path}\0{new if new is not None else 'deleted'}" for path, new in entries)
    return hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest()


def text_id(text: str | None) -> str | None:
    """A content id for a text file in `change_key`; None for a deletion."""
    return None if text is None else "text " + hashlib.sha256(text.encode("utf-8")).hexdigest()


def proposal_marker(sha256: str) -> str:
    return f"<!-- sdlc-proposal:{sha256} -->"


def file_diff(path: str, old: str | None, new: str | None) -> str:
    """A `git apply`-able unified diff of one text file; None is a missing file."""
    if old == new:
        return ""
    a = (old or "").splitlines(keepends=True)
    b = (new or "").splitlines(keepends=True)
    out = [f"diff --git a/{path} b/{path}\n"]
    if old is None:
        out.append("new file mode 100644\n")
    elif new is None:
        out.append("deleted file mode 100644\n")
    src = "/dev/null" if old is None else f"a/{path}"
    dst = "/dev/null" if new is None else f"b/{path}"
    for line in difflib.unified_diff(a, b, src, dst):
        if line.endswith("\n"):
            out.append(line)
        else:
            out.append(line + "\n\\ No newline at end of file\n")
    return "".join(out)


def fenced(text: str, lang: str = "diff") -> str:
    """`text` in a code fence longer than any backtick run inside it."""
    runs = [len(m) for m in re.findall(r"`+", text)]
    fence = "`" * max(3, max(runs, default=0) + 1)
    body = text if text.endswith("\n") else text + "\n"
    return f"{fence}{lang}\n{body}{fence}"


def _named(paths: Iterable[str]) -> list[str]:
    paths = list(paths)
    lines = [f"- `{p}`" for p in paths[:LISTED_PATHS]]
    if len(paths) > LISTED_PATHS:
        lines.append(f"- and {len(paths) - LISTED_PATHS} more")
    return lines


def proposal_text(state: RunState, proposal: Proposal, note: str | None = None) -> str:
    """The PR or issue body, without the marker (the adapter puts it first)."""
    who = proposal.agent_role or "Zamolxe (Orchestrator)"
    lines = [
        f"{who} proposed this factory change in run `{state.run_id}`, scope "
        f"`{proposal.scope}`, step {proposal.seq}. The orchestrator captured it and reverted it "
        "on the agent's branch (AD-26).",
        "",
        "Paths:",
        *_named(proposal.paths),
        "",
        f"Labelled `{OWNER_REVIEW_LABEL}`: Zamolxe (Orchestrator) never merges or auto-merges "
        "it. Review it, then merge or close it.",
    ]
    if note:
        lines += ["", note]
    return "\n".join(lines)


def _file_chunks(patch: str) -> list[tuple[str, str]]:
    """The patch split per file: (path, text), each starting at its `diff --git` line."""
    starts = [m.start() for m in re.finditer(r"^diff --git ", patch, re.MULTILINE)]
    if not starts:
        return [("(patch)", patch)] if patch else []
    if starts[0] != 0:
        starts.insert(0, 0)
    chunks = []
    for i, start in enumerate(starts):
        text = patch[start : starts[i + 1] if i + 1 < len(starts) else len(patch)]
        m = re.match(r"diff --git a/(\S+) ", text)
        chunks.append((m.group(1) if m else "(patch)", text))
    return chunks


def issue_text(state: RunState, proposal: Proposal, patch: str, note: str | None) -> str:
    """An issue body with the patch text, for a proposal that is not a PR. It stays under
    `BODY_LIMIT`: whole files are left out from the end, and named, when the patch is long."""
    head = (
        proposal_text(state, proposal, note)
        + "\n\nApply it with `git apply --3way` on a branch cut from `main`:\n\n"
    )
    chunks = _file_chunks(patch)
    for keep in range(len(chunks), -1, -1):
        shown = "".join(text for _, text in chunks[:keep])
        omitted = [path for path, _ in chunks[keep:]]
        tail = ""
        if omitted:
            tail = (
                "\n\nLeft out to fit GitHub's size limit (the whole patch stays in the "
                f"orchestrator's local run directory on the runner, as `{proposal.patch}`):\n"
                + "\n".join(_named(omitted))
            )
        body = head + (fenced(shown) if shown else "(no file fits)") + tail
        if len(body) <= BODY_LIMIT:
            return body
    return head[:BODY_LIMIT]


def factory_rules(settings: Settings) -> str:
    """The paragraph every writing prompt gets as `${factory_rules}` (AD-26)."""

    def names(entries: tuple[str, ...]) -> str:
        return ", ".join(f"`{e}`" for e in entries) or "(none)"

    return (
        "Factory changes (AD-26): editing a file under "
        f"{names(settings.protected_paths)}, or the `sdlc-commands` block in `AGENTS.md` (the "
        "commands the gates run, ADR 0021), is how you propose a change to the factory. It has "
        "no effect on your own branch: the orchestrator captures the edit as an "
        f"`{OWNER_REVIEW_LABEL}` proposal (a PR or an issue that only the owner merges), then "
        "reverts it before it commits. Propose only what your task needs, and say why in your "
        f"result. {names(settings.owner_only_paths)} are off limits: edits there are reverted "
        f"and never proposed. On a learn branch, edits under {names(settings.review_paths)} "
        f"become an `{OWNER_REVIEW_LABEL}` issue too, never a commit."
    )


def owner_review_lines(state: RunState) -> list[str]:
    """`sdlc status`: the proposals waiting for the owner (AD-26)."""
    out = []
    for x in state.proposals:
        paths = ", ".join(x.paths[:5]) + (", ..." if len(x.paths) > 5 else "")
        who = x.agent_role or "-"
        if x.status is ProposalStatus.DELIVERED:
            where = f"PR #{x.number}" if x.kind == "pr" else f"issue #{x.number}"
        elif x.status is ProposalStatus.FAILED:
            where = f"not delivered ({x.reason or 'error'}); sdlc resume retries"
        else:
            where = "pending"
        out.append(f"owner review: {where}  {x.scope} by {who}: {paths}")
    return out
