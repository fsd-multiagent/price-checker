"""Project commands run by script steps (T-8): `tests`, `terraform_plan`, `fr_ac_check`.

`tests` and `terraform_plan` run the commands of a fenced `yaml` block tagged `sdlc-commands`
under "Running and verifying" in `AGENTS.md`:

    ```yaml sdlc-commands
    tests:
      - uv run --directory orchestrator pytest -q
    ```

The block is read from `origin/main`, and the gate fails when the worktree's block differs, so
a gated agent cannot change what its own gate runs. Each command is split with `shlex` and run
without a shell, with the worktree as cwd: any token with a shell operator, `cd`, and anything
the guard hook denies (`terraform apply|destroy`, `git push`, `gh pr merge`, ssh, rsync to a
host) is refused. A missing block or name fails closed. All commands of a name share one
deadline.
"""

from __future__ import annotations

import re
import shlex
import textwrap
import time
from pathlib import Path

import yaml

from sdlc.adapters.proc import run_proc
from sdlc.core.fr_ac import check_prds
from sdlc.ports import AdapterError, StepKey

AGENTS_MD = "AGENTS.md"
SECTION = "Running and verifying"
PRD_DIR = "_bmad-output/planning-artifacts/prds"
BASE_REF = "origin/main"
_OPERATORS = ("&&", "||", "|", ";", "<", ">", "&")
_SSH = frozenset({"ssh", "scp", "sftp", "mosh", "autossh", "sshpass"})
_OUTPUT_LIMIT = 20_000

_FENCE = re.compile(
    r"^(?P<indent>[ \t]*)(?P<fence>`{3,}|~{3,})[ \t]*yaml[ \t]+sdlc-commands[ \t]*\n"
    r"(?P<body>.*?)^[ \t]*(?P=fence)[ \t]*$",
    re.MULTILINE | re.DOTALL,
)
_FENCE_LINE = re.compile(r"^[ \t]*(`{3,}|~{3,})")
_HEADING = re.compile(r"^(#{1,6})[ \t]+\S")
_REMOTE = re.compile(r"^(?:[^/\s:]+@)?[^/\s:]+::?")  # host:path, user@host:path, host::module


class CommandsError(ValueError):
    pass


def _section(text: str, title: str) -> str | None:
    """The body under the `title` heading, up to the next heading of the same or higher level.

    Lines inside fenced code (```` ``` ```` or `~~~`) never count as headings.
    """
    lines = text.splitlines(keepends=True)
    fence: str | None = None
    start: int | None = None
    level = 0
    for i, line in enumerate(lines):
        m = _FENCE_LINE.match(line)
        if m:
            mark = m.group(1)
            if fence is None:
                fence = mark
            elif mark[0] == fence[0] and len(mark) >= len(fence):
                fence = None
            continue
        if fence is not None:
            continue
        h = _HEADING.match(line)
        if h is None:
            continue
        if start is None:
            if line.lstrip("#").strip() == title:
                start, level = i + 1, len(h.group(1))
        elif len(h.group(1)) <= level:
            return "".join(lines[start:i])
    return None if start is None else "".join(lines[start:])


def parse_commands(text: str) -> dict[str, tuple[str, ...]]:
    """The `sdlc-commands` block under "Running and verifying". Raises CommandsError."""
    section = _section(text, SECTION)
    if section is None:
        raise CommandsError(f'no "{SECTION}" section in {AGENTS_MD}')
    m = _FENCE.search(section)
    if m is None:
        raise CommandsError(f"no sdlc-commands block in {AGENTS_MD}")
    try:
        data = yaml.safe_load(textwrap.dedent(m.group("body")))
    except yaml.YAMLError as exc:
        raise CommandsError(f"sdlc-commands block is not valid YAML: {exc}") from exc
    if not isinstance(data, dict):
        raise CommandsError("sdlc-commands block must map a name to a list of commands")
    out: dict[str, tuple[str, ...]] = {}
    for name, cmds in data.items():
        if not isinstance(cmds, list) or not all(isinstance(c, str) and c.strip() for c in cmds):
            raise CommandsError(f"sdlc-commands '{name}' must be a list of command strings")
        out[str(name)] = tuple(cmds)
    return out


def _block_span(text: str) -> tuple[int, int] | None:
    """Start and end offsets in `text` of the `sdlc-commands` fenced block (fences included)
    under "Running and verifying", so a copy of the same text elsewhere is never touched."""
    section = _section(text, SECTION)
    if section is None:
        return None
    m = _FENCE.search(section)
    if m is None:
        return None
    offset = text.find(section)
    return offset + m.start(), offset + m.end()


def restore_commands_block(current: str | None, base: str) -> tuple[str, str] | None:
    """`current` with its `sdlc-commands` block put back as it is in `base`, and what was
    restored (`"sdlc-commands block"` or `"whole file"`); None when nothing needs restoring
    (ADR 0021).

    Other edits to the file are kept. When `current` is gone, or has lost the block or its
    section, the whole of `base` comes back: there is nothing to splice into, and the agent's
    other edits to the file are lost (the caller reports that).
    """
    wanted = _block_span(base)
    if wanted is None:
        return None
    block = base[wanted[0] : wanted[1]]
    have = None if current is None else _block_span(current)
    if current is None or have is None:
        return base, "whole file"
    if current[have[0] : have[1]] == block:
        return None
    return current[: have[0]] + block + current[have[1] :], "sdlc-commands block"


def apply_commands_block(base: str, agent_text: str | None) -> str | None:
    """The inverse of `restore_commands_block` (AD-26): `base` (origin/main's file) with the
    agent's `sdlc-commands` block spliced in, every other line as in `base`. None when the
    agent's file has no block, `base` has nowhere to splice it, or the blocks are equal."""
    if agent_text is None:
        return None
    have = _block_span(agent_text)
    wanted = _block_span(base)
    if have is None or wanted is None:
        return None
    block = agent_text[have[0] : have[1]]
    if base[wanted[0] : wanted[1]] == block:
        return None
    return base[: wanted[0]] + block + base[wanted[1] :]


def commands_block(text: str | None) -> str | None:
    """The `sdlc-commands` fenced block (fences included) under "Running and verifying", or
    None when `text` is missing or has no block there."""
    if text is None:
        return None
    span = _block_span(text)
    return None if span is None else text[span[0] : span[1]]


async def branch_point(worktree: str) -> str | None:
    """`git merge-base HEAD origin/main`: the commit on main this branch started from
    (ADR 0023). None when it cannot be found."""
    try:
        res = await run_proc(("git", "merge-base", "HEAD", BASE_REF), cwd=worktree, timeout_s=60)
    except AdapterError:
        return None
    sha = res.stdout.strip() if res.ok else ""
    return sha or None


def denied(argv: list[str]) -> str | None:
    """Why the guard hook would deny this command, or None. Checked at every position, so a
    wrapper (`uv run`, `env`) does not hide the program."""
    for i, tok in enumerate(argv):
        prog = tok.rsplit("/", 1)[-1]
        rest = argv[i + 1 :]
        if prog in ("terraform", "tofu") and {"apply", "destroy"} & set(rest):
            return f"{prog} apply/destroy"
        if prog == "git" and "push" in rest:
            return "git push"
        if prog == "gh" and any(rest[j : j + 2] == ["pr", "merge"] for j in range(len(rest))):
            return "gh pr merge"
        if prog in _SSH:
            return prog
        if prog == "rsync" and any(
            t in ("-e", "--rsh") or t.startswith("--rsh=") or _REMOTE.match(t) for t in rest
        ):
            return "rsync to a remote host"
    return None


def split_command(cmd: str) -> list[str]:
    try:
        argv = shlex.split(cmd)
    except ValueError as exc:
        raise CommandsError(f"command {cmd!r} cannot be split: {exc}") from exc
    if not argv:
        raise CommandsError("empty command")
    bad = sorted({op for tok in argv for op in _OPERATORS if op in tok})
    if bad or "cd" in argv:
        raise CommandsError(
            f"command {cmd!r} uses shell syntax {bad or ['cd']}; commands run without a shell"
        )
    why = denied(argv)
    if why:
        raise CommandsError(f"command {cmd!r} is denied by the guard rules ({why})")
    return argv


class ProjectCommands:
    async def run(
        self, name: str, worktree: str, timeout_s: float, key: StepKey, factory: bool = False
    ) -> tuple[int, str]:
        """Run the `name` commands of the block at the branch point (ADR 0023). A factory
        story (AD-27) may change the block, but its new block is agent-written and never runs
        before the owner merges it; any other branch that changed the block fails (ADR 0004)."""
        if not Path(worktree).is_dir():
            return 1, f"{name}: worktree {worktree} does not exist"
        if name == "fr_ac_check":
            return self._fr_ac(worktree)
        try:
            text = _read(Path(worktree) / AGENTS_MD)
        except OSError as exc:
            return 1, f"no {name} commands in {AGENTS_MD}: cannot read it: {exc}"
        if text is None:
            return 1, f"no {name} commands in {AGENTS_MD} ({AGENTS_MD} not found)"
        try:
            block = parse_commands(text)
        except CommandsError as exc:
            return 1, f"no {name} commands in {AGENTS_MD}: {exc}"
        point = await self._branch_point(worktree)
        base = await self._block_at(worktree, point or BASE_REF)
        if base != block and (not factory or base is None):
            where = f"the branch point {point[:12]} on {BASE_REF}" if point else BASE_REF
            return 1, (
                f"sdlc-commands in the worktree differ from {where}: this branch changed "
                f"the gate block (ADR 0004)"
            )
        cmds = list(base.get(name) or ()) if base else []
        if not cmds:
            return 1, f"no {name} commands in {AGENTS_MD}"
        try:
            argvs = [split_command(c) for c in cmds]
        except CommandsError as exc:
            return 1, f"{name}: {exc}"
        deadline = time.monotonic() + timeout_s
        log: list[str] = []
        if base != block:
            log.append(f"factory story: the branch point's {name} commands; the story's new "
                       "block runs only after the owner merges it (AD-27)")  # fmt: skip
        for cmd, argv in zip(cmds, argvs, strict=True):
            left = deadline - time.monotonic()
            if left <= 0:
                log.append(f"timed out after {timeout_s:g}s before {cmd!r}")
                return 124, _clip("\n".join(log))
            try:
                res = await run_proc(argv, cwd=worktree, timeout_s=left)
            except AdapterError as exc:  # the executable is missing
                log.append(f"$ {cmd}\n{exc}")
                return 127, _clip("\n".join(log))
            log.append(f"$ {cmd}\n{res.stdout}{res.stderr}")
            if res.timed_out:
                log.append(f"timed out after {timeout_s:g}s")
                return 124, _clip("\n".join(log))
            if res.returncode != 0:
                return res.returncode or 1, _clip("\n".join(log))
        return 0, _clip("\n".join(log))

    async def _branch_point(self, worktree: str) -> str | None:
        """The gate compares the worktree's block with the block at the branch point, not
        with today's main, so a change to the block on main after the branch started does not
        fail the branch. The commands run are the ones at the branch point: trusted (they are
        on main) and matching the code the branch was built on."""
        return await branch_point(worktree)

    async def _block_at(self, worktree: str, ref: str) -> dict[str, tuple[str, ...]] | None:
        """The block as committed at `ref`; None when it cannot be read or parsed."""
        try:
            res = await run_proc(("git", "show", f"{ref}:{AGENTS_MD}"), cwd=worktree, timeout_s=60)
        except AdapterError:
            return None
        if not res.ok:
            return None
        try:
            return parse_commands(res.stdout)
        except CommandsError:
            return None

    def _fr_ac(self, worktree: str) -> tuple[int, str]:
        root = Path(worktree)
        try:
            files = {
                str(p.relative_to(root)): p.read_text(encoding="utf-8")
                for p in sorted((root / PRD_DIR).rglob("*.md"))
                if p.is_file()
            }
        except (OSError, UnicodeDecodeError) as exc:
            return 1, f"fr_ac_check: cannot read the PRD files: {exc}"
        return check_prds(files)


def _read(path: Path) -> str | None:
    """None when the file is missing; OSError when it is unreadable or not UTF-8."""
    try:
        return path.read_text(encoding="utf-8")
    except (FileNotFoundError, IsADirectoryError, NotADirectoryError):
        return None
    except UnicodeDecodeError as exc:
        raise OSError(f"{path} is not UTF-8: {exc}") from exc


def _clip(text: str) -> str:
    return text if len(text) <= _OUTPUT_LIMIT else "...\n" + text[-_OUTPUT_LIMIT:]
