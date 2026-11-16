#!/usr/bin/env python3
"""PreToolUse guard for every Claude Code session in this repo (plan step 0.4, docs/automation-plan.md §5).

It reads the hook payload on stdin. To block a call it prints a deny decision; to let it through it prints
nothing. It blocks:

  G1  terraform/tofu apply, destroy, import, taint, untaint, force-unlock and state rm/mv/push
      (a human runs apply; §6)
  G2  git push to main/master (including a bare `git push` while on main, --all and --mirror),
      `gh pr merge`, and `gh api` calls that touch main or branch protection
  G3  ssh, scp, sftp, mosh, `az ssh`, `az vm run-command`, and rsync over ssh
      (deploys go through CI, never from an agent)
  G4  network access to a retailer that is not marked Approved in docs/source-register.md,
      through WebFetch, MCP tools that take a URL, or network tools in Bash
  G5  changes to the retailer approval status, and Bash writes to guardrail files
      (only a human approves a retailer; S-1)

Limits: this guard parses commands; it cannot sandbox them. A script file that fetches a URL on its own is not
seen. The application-level control is the shared fetch client (coding.md C-12, security-compliance.md S-1).

Register contract (step 0.5): docs/source-register.md holds a Markdown table whose header has a "Domain"
column and a "Status" column. A row is approved only when its Status cell starts with "Approved".
"""
from __future__ import annotations

import json
import os
import re
import shlex
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(os.environ.get("CLAUDE_PROJECT_DIR") or Path(__file__).resolve().parents[2])
REGISTER = ROOT / "docs" / "source-register.md"

# Retailers that are blocked even if the register is missing or does not list them.
KNOWN_RETAILERS = {"emag.ro", "evomag.ro", "cel.ro", "altex.ro", "pcgarage.ro"}

PROTECTED_PATHS = (
    "docs/source-register.md",
    ".claude/settings.json",
    ".claude/settings.local.json",
    ".claude/hooks/",
)

TF_BLOCKED = {"apply", "destroy", "import", "taint", "untaint", "force-unlock"}
TF_STATE_BLOCKED = {"rm", "mv", "push", "replace-provider"}
PROTECTED_BRANCHES = {"main", "master"}
SSH_PROGS = {"ssh", "scp", "sftp", "mosh", "autossh", "sshpass"}
NET_PROGS = {"curl", "wget", "http", "https", "httpie", "xh", "nc", "ncat", "telnet", "lynx", "w3m",
             "aria2c", "open", "playwright", "chromium", "google-chrome"}
INLINE_CODE = {"python": {"-c"}, "python3": {"-c"}, "node": {"-e", "--eval", "-p", "--print"},
               "deno": {"eval"}, "bun": {"-e", "--eval"}, "ruby": {"-e"}, "perl": {"-e", "-E"},
               "jshell": set(), "npx": set(), "pnpx": set(), "bunx": set()}
WRAPPERS = {"sudo", "env", "command", "exec", "nohup", "time", "nice", "timeout", "xargs", "caffeinate"}
SHELLS = {"bash", "sh", "zsh", "dash", "ksh"}

HOST_RE = re.compile(r"(?i)(?<![\w@.-])((?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,})(?![\w-])")
URL_RE = re.compile(r"(?i)\b(?:https?|wss?)://[^\s'\"<>]+")
WRITE_RE = re.compile(r"(?<![0-9&<])>(?!&)|\btee\b|\b(?:sed|perl)\b[^|]*\s-i|\b(?:mv|cp|rm|chmod|truncate|ln|dd|install)\b"
                      r"|\bgit\s+(?:checkout|restore|rm|mv|apply)\b")


# --------------------------------------------------------------------------- register

def read_register(text: str | None = None) -> tuple[set[str], set[str]]:
    """Return (all register domains, approved domains)."""
    if text is None:
        try:
            text = REGISTER.read_text(encoding="utf-8")
        except OSError:
            return set(), set()
    listed: set[str] = set()
    approved: set[str] = set()
    d_col = s_col = None
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("|"):
            d_col = s_col = None
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        lower = [c.lower().strip("* ") for c in cells]
        if "domain" in lower and "status" in lower:
            d_col, s_col = lower.index("domain"), lower.index("status")
            continue
        if d_col is None or set(line) <= set("|-: "):
            continue
        if max(d_col, s_col) >= len(cells):
            continue
        domain = norm_domain(cells[d_col])
        if not domain:
            continue
        listed.add(domain)
        if cells[s_col].strip("*_` ").lower().startswith("approved"):
            approved.add(domain)
    return listed, approved


def norm_domain(raw: str) -> str:
    raw = raw.strip().strip("`*_<> ").lower()
    if "://" in raw:
        raw = urlparse(raw).hostname or ""
    raw = raw.split("/")[0].split(":")[0].rstrip(".")
    return raw[4:] if raw.startswith("www.") else raw


def retailer_problem(host: str, listed: set[str], approved: set[str]) -> str | None:
    host = norm_domain(host)
    for dom in KNOWN_RETAILERS | listed:
        if host == dom or host.endswith("." + dom):
            if dom in approved:
                return None
            return (f"G4: {host} belongs to retailer {dom}, which is not marked Approved in "
                    f"docs/source-register.md. Only a human approves a retailer (S-1). Use recorded "
                    f"fixtures in fixtures/<retailer>/ instead (T-2).")
    return None


# --------------------------------------------------------------------------- bash

def segments(cmd: str) -> list[str]:
    """Split a shell command on ; | & newline ` $( ) — but only outside quotes."""
    out, cur, quote, i = [], [], None, 0
    while i < len(cmd):
        ch = cmd[i]
        if quote:
            if ch == "\\" and quote == '"' and i + 1 < len(cmd):
                cur.append(cmd[i:i + 2])
                i += 2
                continue
            if ch == quote:
                quote = None
            cur.append(ch)
        elif ch in "'\"":
            quote = ch
            cur.append(ch)
        elif ch == "\\" and i + 1 < len(cmd):
            cur.append(cmd[i:i + 2])
            i += 2
            continue
        elif ch in ";|&\n`()" or (ch == "$" and cmd[i + 1:i + 2] == "("):
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
        i += 1
    out.append("".join(cur))
    return [s for s in out if s.strip()]


def tokens_of(segment: str) -> list[str]:
    try:
        toks = shlex.split(segment, posix=True)
    except ValueError:
        toks = segment.split()
    while toks and (re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", toks[0]) or os.path.basename(toks[0]) in WRAPPERS):
        toks = toks[1:]
        while toks and toks[0].startswith("-"):  # wrapper flags, e.g. `env -i`, `timeout -s`
            toks = toks[1:]
    if toks and os.path.basename(toks[0]) == "timeout" and len(toks) > 1:
        toks = toks[2:]
    return toks


def current_branch(cwd: str | None) -> str:
    try:
        out = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=cwd or ROOT,
                             capture_output=True, text=True, timeout=5)
        return out.stdout.strip()
    except Exception:
        return ""


def check_git(toks: list[str], cwd: str | None) -> str | None:
    i, git_cwd = 1, cwd
    while i < len(toks) and toks[i].startswith("-"):
        if toks[i] in ("-C", "-c", "--git-dir", "--work-tree", "--namespace"):
            if toks[i] == "-C" and i + 1 < len(toks):
                git_cwd = os.path.join(cwd or str(ROOT), toks[i + 1])
            i += 2
        else:
            i += 1
    if i >= len(toks) or toks[i] != "push":
        return None
    args = toks[i + 1:]
    if any(a in ("--all", "--mirror") for a in args):
        return "G2: `git push --all/--mirror` would push main. Push only your story branch."
    positionals, j = [], 0
    while j < len(args):
        a = args[j]
        if a in ("--repo", "-o", "--push-option", "--receive-pack", "--exec"):
            j += 2
            continue
        if not a.startswith("-"):
            positionals.append(a)
        j += 1
    refspecs = positionals[1:]
    branch = current_branch(git_cwd)
    if not refspecs:
        if branch in PROTECTED_BRANCHES:
            return f"G2: a bare `git push` while on {branch} pushes to {branch}. Work on a story branch."
        return None
    for spec in refspecs:
        spec = spec.lstrip("+")
        dst = spec.split(":", 1)[1] if ":" in spec else spec
        if dst == "HEAD":
            dst = branch
        dst = dst.removeprefix("refs/heads/")
        if dst in PROTECTED_BRANCHES:
            return f"G2: pushing to {dst} is blocked. Changes reach main only through a PR with green CI."
    return None


def check_tokens(toks: list[str], cwd: str | None, listed: set[str], approved: set[str],
                 depth: int = 0) -> str | None:
    if not toks:
        return None
    prog = os.path.basename(toks[0])

    if prog in SHELLS and depth < 3:
        for k, t in enumerate(toks[1:], 1):
            if t.startswith("-") and "c" in t and k + 1 < len(toks):
                return check_command(toks[k + 1], cwd, listed, approved, depth + 1)

    if prog in ("terraform", "tofu"):
        rest = [t for t in toks[1:] if not t.startswith("-")]
        if rest and rest[0] in TF_BLOCKED:
            return f"G1: `{prog} {rest[0]}` is blocked. Stage 6 ends at a successful plan; a human runs apply."
        if len(rest) > 1 and rest[0] == "state" and rest[1] in TF_STATE_BLOCKED:
            return f"G1: `{prog} state {rest[1]}` changes real state and is blocked."

    if prog == "git":
        problem = check_git(toks, cwd)
        if problem:
            return problem

    if prog == "gh" and len(toks) > 2:
        if toks[1] == "pr" and toks[2] == "merge":
            return "G2: agents do not merge PRs. The PR auto-merges when CI is green."
        if toks[1] == "api" and re.search(r"refs/heads/(main|master)\b|branches/(main|master)\b|/protection",
                                          " ".join(toks[2:])):
            return "G2: `gh api` calls that change main or branch protection are blocked."

    if prog in SSH_PROGS:
        return f"G3: `{prog}` is blocked in agent sessions. Deploys go through CI with the deploy key."
    if prog == "az" and len(toks) > 2 and (toks[1] == "ssh" or toks[1:3] == ["vm", "run-command"]):
        return "G3: remote access to the VM from an agent session is blocked. Deploys go through CI."
    if prog == "rsync" and (any(t in ("-e", "--rsh") or t.startswith("--rsh=") for t in toks)
                            or any(re.match(r"^[^/\s]+:", t) for t in toks[1:] if not t.startswith("-"))):
        return "G3: rsync to a remote host is blocked in agent sessions."

    inline = INLINE_CODE.get(prog)
    is_net = prog in NET_PROGS or (inline is not None and (not inline or any(t in inline for t in toks[1:])))
    if is_net:
        text = " ".join(toks[1:])
        for host in [urlparse(u).hostname or "" for u in URL_RE.findall(text)] + HOST_RE.findall(text):
            problem = retailer_problem(host, listed, approved)
            if problem:
                return problem
    return None


def check_command(cmd: str, cwd: str | None, listed: set[str], approved: set[str], depth: int = 0) -> str | None:
    for seg in segments(cmd):
        if any(p in seg for p in PROTECTED_PATHS) and WRITE_RE.search(seg):
            return ("G5: Bash may not modify docs/source-register.md or the guardrail files "
                    "(.claude/settings*.json, .claude/hooks/). Use Edit or Write so the change is checked.")
        problem = check_tokens(tokens_of(seg), cwd, listed, approved, depth)
        if problem:
            return problem
    return None


# --------------------------------------------------------------------------- edits

def check_register_edit(tool: str, inp: dict) -> str | None:
    path = inp.get("file_path") or inp.get("notebook_path") or ""
    try:
        if Path(path).resolve() != REGISTER.resolve():
            return None
    except OSError:
        return None
    before = REGISTER.read_text(encoding="utf-8") if REGISTER.exists() else ""
    if tool == "Write":
        after = inp.get("content", "")
    elif tool == "Edit":
        old, new = inp.get("old_string", ""), inp.get("new_string", "")
        after = before.replace(old, new) if inp.get("replace_all") else before.replace(old, new, 1)
    elif tool == "MultiEdit":
        after = before
        for e in inp.get("edits", []):
            old, new = e.get("old_string", ""), e.get("new_string", "")
            after = after.replace(old, new) if e.get("replace_all") else after.replace(old, new, 1)
    else:
        return "G5: docs/source-register.md may only be changed with Edit or Write."
    newly = read_register(after)[1] - read_register(before)[1]
    if newly:
        return (f"G5: this edit would mark {', '.join(sorted(newly))} as Approved. Only a human approves a "
                f"retailer (S-1). Draft the row with Status `Proposed` and stop.")
    return None


# --------------------------------------------------------------------------- main

def strings_in(value) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        return [s for v in value.values() for s in strings_in(v)]
    if isinstance(value, list):
        return [s for v in value for s in strings_in(v)]
    return []


def decide(payload: dict) -> str | None:
    tool = payload.get("tool_name", "")
    inp = payload.get("tool_input") or {}
    cwd = payload.get("cwd")
    listed, approved = read_register()

    if tool == "Bash":
        return check_command(inp.get("command", ""), cwd, listed, approved)
    if tool in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
        return check_register_edit(tool, inp)
    if tool == "WebFetch" or tool.startswith("mcp__"):
        for s in strings_in(inp):
            for url in URL_RE.findall(s):
                problem = retailer_problem(urlparse(url).hostname or "", listed, approved)
                if problem:
                    return problem
    return None


def main() -> int:
    payload = json.load(sys.stdin)
    reason = decide(payload)
    if reason:
        print(json.dumps({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": f"[guard] {reason}",
        }}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
