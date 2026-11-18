"""ADR 0021: agent branches never carry a change to the `sdlc-commands` gate block."""

from __future__ import annotations

from pathlib import Path

from conftest import REPO, make_dry_run, run_dry

from sdlc.adapters.commands import AGENTS_MD, parse_commands, restore_commands_block
from sdlc.core.proposals import factory_rules

BASE = """# Project

## Running and verifying

- Tests:

  ```yaml sdlc-commands
  tests:
    - uv run --directory orchestrator pytest -q
    - backend/gradlew -p backend check
  ```

## Conventions

- Keep it short.
"""

WEAKENED = BASE.replace("    - backend/gradlew -p backend check\n", "").replace(
    "- Keep it short.", "- Keep it short. New doc line from the agent."
)
WEAK_BLOCK = WEAKENED[WEAKENED.index("  ```yaml") : WEAKENED.index("  ```\n\n## Conv") + 6]


def test_gate_guard_unchanged_block_is_left_alone():
    edited = BASE.replace("- Keep it short.", "- Keep it short. Extra.")
    assert restore_commands_block(edited, BASE) is None


def test_gate_guard_changed_block_is_restored_and_other_edits_kept():
    restored = restore_commands_block(WEAKENED, BASE)
    assert restored is not None
    text, what = restored
    assert what == "sdlc-commands block"
    assert parse_commands(text) == parse_commands(BASE)
    assert "New doc line from the agent." in text


def test_gate_guard_splices_the_gating_copy_not_an_earlier_quote():
    """The same weakened block quoted earlier as an example stays; the gating copy is fixed."""
    quoted = "# Project\n\n## Example\n\n" + WEAK_BLOCK + "\n" + WEAKENED.split("# Project\n", 1)[1]
    text, what = restore_commands_block(quoted, BASE)
    assert what == "sdlc-commands block"
    assert parse_commands(text) == parse_commands(BASE)
    assert text.index(WEAK_BLOCK) < text.index("## Running and verifying")  # quote untouched


def test_gate_guard_removed_block_restores_the_whole_file():
    gone = BASE.replace("## Running and verifying", "## Running")
    assert restore_commands_block(gone, BASE) == (BASE, "whole file")


def test_gate_guard_deleted_file_is_restored():
    assert restore_commands_block(None, BASE) == (BASE, "whole file")


def test_gate_guard_base_without_block_has_nothing_to_protect():
    assert restore_commands_block(WEAKENED, "# no block here\n") is None


def _weaken_every_worktree(dr) -> None:
    git, fs = dr.ports.git, dr.ports.fs

    async def show(ref: str, path: str) -> str | None:
        return BASE if path == AGENTS_MD else None

    real_read = fs.read_text

    def read_text(path: str) -> str | None:
        if path.endswith(f"/{AGENTS_MD}") and path not in fs.files:
            # Story branches revert AGENTS.md to their head (AD-21): the fake has no content
            # to revert to, so a story worktree reads the base file.
            return BASE if "/story/" in path else WEAKENED
        return real_read(path)

    git.show = show  # type: ignore[method-assign]
    fs.read_text = read_text  # type: ignore[method-assign]


async def test_gate_guard_every_non_story_commit_restores_the_block(tmp_path):
    """A dry run where every worktree's AGENTS.md has a weakened block: every agent commit and
    sprint-status commit outside a story scope puts the base block back; story scopes rely on
    the bookkeeping revert and get no restore."""
    dr = make_dry_run(tmp_path)
    _weaken_every_worktree(dr)
    assert await run_dry(dr) == 0

    commits = [c for c in dr.ports.git.calls if c[0] == "commit"]
    # AD-26: the block change is proposed once (one patch, one proposal) on its own branch.
    committed = {c[1] for c in commits if "/story/" not in c[1] and "/proposal/" not in c[1]}
    assert [x.paths for x in dr.state.proposals] == [(AGENTS_MD,)]
    restores = [line for line in dr.lines if f"restored {AGENTS_MD} (sdlc-commands block)" in line]
    assert committed and restores
    assert not any("on story:" in line for line in restores)

    written = [p for p in dr.ports.fs.files if p.endswith(f"/{AGENTS_MD}")]
    # every non-story worktree that committed had its block restored first (once is enough:
    # later commits in the same worktree already see the restored file)
    assert committed <= {p.rsplit("/", 1)[0] for p in written}
    assert written and not any("/story/" in p for p in written)
    assert all(parse_commands(dr.ports.fs.files[p]) == parse_commands(BASE) for p in written)
    assert all("New doc line from the agent." in dr.ports.fs.files[p] for p in written)

    events = [e for e in dr.events() if e["type"] == "paths_reverted"]
    assert events and all(AGENTS_MD in e["paths"] for e in events)
    assert all(e["scope"].split(":")[0] != "story" for e in events)


async def test_gate_guard_origin_without_agents_md_changes_nothing(tmp_path):
    dr = make_dry_run(tmp_path)  # the fake git returns None for AGENTS.md on origin/main
    assert await run_dry(dr) == 0
    assert not any(line for line in dr.lines if f"restored {AGENTS_MD}" in line)
    assert not [e for e in dr.events() if e["type"] == "paths_reverted"]


def test_gate_guard_prompts_tell_agents_the_block_is_off_limits(pipeline):
    """The rules come from `${factory_rules}` (AD-26), which names the block."""
    rules = factory_rules(pipeline.settings)
    assert "`sdlc-commands` block" in rules and "ADR 0021" in rules
    for name in ("knowledge-keeper.md", "infra.md", "architecture.md"):
        text = (Path(REPO) / "sdlc" / "prompts" / name).read_text(encoding="utf-8")
        assert "${factory_rules}" in text, name
