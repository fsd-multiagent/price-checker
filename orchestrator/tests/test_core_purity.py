"""AC-3: `core/` imports no I/O, no subprocess, no asyncio, no os, no adapters, no app (AD-1)."""

from __future__ import annotations

import ast
from pathlib import Path

CORE = Path(__file__).resolve().parents[1] / "src" / "sdlc" / "core"
FORBIDDEN = {
    "subprocess",
    "asyncio",
    "os",
    "pathlib",
    "shutil",
    "tempfile",
    "socket",
    "http",
    "urllib.request",
    "sdlc.adapters",
    "sdlc.app",
    "sdlc.store",
    "sdlc.cli",
    "sdlc.ports",
}
FORBIDDEN_CALLS = {"open", "input", "print", "exec", "eval", "__import__"}


def _imports(tree: ast.AST) -> set[str]:
    names: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names.update(a.name for a in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            names.add(node.module)
            names.update(f"{node.module}.{a.name}" for a in node.names)
    return names


def _banned(name: str) -> bool:
    return any(name == f or name.startswith(f + ".") for f in FORBIDDEN)


def test_ac3_core_imports_no_io_modules():
    files = sorted(CORE.glob("*.py"))
    assert files, "core/ has no modules"
    problems = []
    for f in files:
        tree = ast.parse(f.read_text(encoding="utf-8"))
        problems += [f"{f.name}: imports {n}" for n in sorted(_imports(tree)) if _banned(n)]
        for node in ast.walk(tree):
            if (
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Name)
                and node.func.id in FORBIDDEN_CALLS
            ):
                problems.append(f"{f.name}:{node.lineno}: calls {node.func.id}()")
    assert problems == []


def test_ac3_scanner_catches_a_forbidden_import():
    tree = ast.parse("import subprocess\nfrom sdlc.adapters import fake\nfrom os import path\n")
    assert {n for n in _imports(tree) if _banned(n)} >= {"subprocess", "sdlc.adapters", "os"}
