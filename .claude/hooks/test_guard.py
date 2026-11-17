#!/usr/bin/env python3
"""Tests for guard.py. Run: python3 .claude/hooks/test_guard.py"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import guard  # noqa: E402

REGISTER = """
| Retailer | Domain | Status | Notes |
|---|---|---|---|
| cel.ro | `cel.ro` | **Approved** (2026-11-20) | ok |
| evoMAG | https://www.evomag.ro | Proposed | search disallowed |
| Altex | altex.ro | Rejected | |
"""


def bash(cmd: str) -> str | None:
    listed, approved = guard.read_register(REGISTER)
    return guard.check_command(cmd, str(guard.ROOT), listed, approved)


class Register(unittest.TestCase):
    def test_parse(self):
        listed, approved = guard.read_register(REGISTER)
        self.assertEqual(listed, {"cel.ro", "evomag.ro", "altex.ro"})
        self.assertEqual(approved, {"cel.ro"})

    def test_missing_register_blocks_all_known(self):
        for host in guard.KNOWN_RETAILERS:
            self.assertIsNotNone(guard.retailer_problem("www." + host, set(), set()))


class Terraform(unittest.TestCase):
    def test_blocked(self):
        for cmd in ["terraform apply", "terraform -chdir=infra apply -auto-approve", "cd infra && terraform destroy",
                    "tofu apply", "terraform state rm aws_x.y", "bash -c 'terraform apply'", "sudo terraform apply"]:
            self.assertRegex(bash(cmd) or "", "G1", cmd)

    def test_allowed(self):
        for cmd in ["terraform init", "terraform -chdir=infra plan -out tf.plan", "terraform validate",
                    "terraform fmt -check", "terraform state list"]:
            self.assertIsNone(bash(cmd), cmd)


class GitPush(unittest.TestCase):
    def test_blocked(self):
        for cmd in ["git push origin main", "git push -u origin HEAD:main", "git push origin +feature:refs/heads/main",
                    "git push --force origin master", "git push --all", "git -C . push origin main",
                    "gh pr merge 12 --squash", "gh api -X DELETE repos/o/r/branches/main/protection"]:
            self.assertRegex(bash(cmd) or "", "G2", cmd)

    def test_allowed(self):
        for cmd in ["git push -u origin story/2-1-ean-input", "git push origin HEAD:story/x",
                    "git status", "git commit -m 'main fix'", "gh pr create --fill", "gh pr checks 12"]:
            self.assertIsNone(bash(cmd), cmd)

    def test_bare_push_depends_on_branch(self):
        branch = guard.current_branch(None)
        result = bash("git push")
        if branch in guard.PROTECTED_BRANCHES:
            self.assertRegex(result or "", "G2")
        else:
            self.assertIsNone(result)


class Ssh(unittest.TestCase):
    def test_blocked(self):
        for cmd in ["ssh azureuser@1.2.3.4", "scp a.txt vm:/tmp", "az ssh vm -n app", "az vm run-command invoke -g x",
                    "rsync -av dist/ user@host:/srv", "rsync -e ssh a b"]:
            self.assertRegex(bash(cmd) or "", "G3", cmd)

    def test_allowed(self):
        for cmd in ["ssh-keygen -t ed25519 -f k", "rsync -a src/ dst/", "az account show"]:
            self.assertIsNone(bash(cmd), cmd)


class Network(unittest.TestCase):
    def test_blocked(self):
        for cmd in ["curl -s https://www.evomag.ro/search?q=1", "wget altex.ro", "curl https://www.emag.ro/x",
                    "python3 -c 'import urllib.request as u; u.urlopen(\"https://pcgarage.ro\")'",
                    "npx playwright open https://altex.ro"]:
            self.assertRegex(bash(cmd) or "", "G4", cmd)

    def test_allowed(self):
        for cmd in ["curl -s https://www.cel.ro/cauta/123", "curl https://api.github.com", "grep -r evomag.ro docs",
                    "ls fixtures/altex.ro", "python3 scripts/apply-persona-names.py --check"]:
            self.assertIsNone(bash(cmd), cmd)

    def test_webfetch_and_mcp(self):
        os.environ.pop("X", None)
        with tempfile.TemporaryDirectory() as tmp:
            reg = Path(tmp) / "docs" / "source-register.md"
            reg.parent.mkdir()
            reg.write_text(REGISTER)
            old = guard.REGISTER
            guard.REGISTER = reg
            try:
                self.assertRegex(guard.decide({"tool_name": "WebFetch", "tool_input": {"url": "https://altex.ro/p"}}) or "", "G4")
                self.assertIsNone(guard.decide({"tool_name": "WebFetch", "tool_input": {"url": "https://cel.ro/p"}}))
                self.assertRegex(guard.decide({"tool_name": "mcp__browser__navigate",
                                               "tool_input": {"args": {"url": "https://m.emag.ro/"}}}) or "", "G4")
            finally:
                guard.REGISTER = old


class Protected(unittest.TestCase):
    def test_bash_writes_blocked(self):
        for cmd in ["sed -i '' 's/Proposed/Approved/' docs/source-register.md", "echo x >> docs/source-register.md",
                    "rm .claude/hooks/guard.py", "echo '{}' > .claude/settings.local.json",
                    "cat x | tee .claude/settings.json"]:
            self.assertRegex(bash(cmd) or "", "G5", cmd)

    def test_bash_reads_allowed(self):
        for cmd in ["cat docs/source-register.md", "python3 .claude/hooks/test_guard.py",
                    "grep Approved docs/source-register.md 2>/dev/null"]:
            self.assertIsNone(bash(cmd), cmd)

    def test_register_edits(self):
        with tempfile.TemporaryDirectory() as tmp:
            reg = Path(tmp) / "source-register.md"
            reg.write_text(REGISTER)
            old = guard.REGISTER
            guard.REGISTER = reg
            try:
                approve = {"file_path": str(reg), "old_string": "| Proposed |", "new_string": "| Approved |"}
                self.assertRegex(guard.decide({"tool_name": "Edit", "tool_input": approve}) or "", "G5")
                fact = {"file_path": str(reg), "old_string": "search disallowed", "new_string": "search disallowed by robots"}
                self.assertIsNone(guard.decide({"tool_name": "Edit", "tool_input": fact}))
                add = {"file_path": str(reg), "content": REGISTER + "| PC Garage | pcgarage.ro | Proposed | |\n"}
                self.assertIsNone(guard.decide({"tool_name": "Write", "tool_input": add}))
                sneak = {"file_path": str(reg), "content": REGISTER + "| PC Garage | pcgarage.ro | Approved | |\n"}
                self.assertRegex(guard.decide({"tool_name": "Write", "tool_input": sneak}) or "", "G5")
            finally:
                guard.REGISTER = old


class EndToEnd(unittest.TestCase):
    """Runs the script the way Claude Code does: JSON on stdin, decision on stdout."""

    def run_hook(self, payload: dict) -> str:
        out = subprocess.run([sys.executable, str(HERE / "guard.py")], input=json.dumps(payload),
                             capture_output=True, text=True, check=True)
        return out.stdout

    def test_deny_shape(self):
        out = json.loads(self.run_hook({"tool_name": "Bash", "tool_input": {"command": "terraform apply"}}))
        self.assertEqual(out["hookSpecificOutput"]["permissionDecision"], "deny")

    def test_allow_is_silent(self):
        self.assertEqual(self.run_hook({"tool_name": "Bash", "tool_input": {"command": "ls"}}), "")
        self.assertEqual(self.run_hook({"tool_name": "Read", "tool_input": {"file_path": "README.md"}}), "")


if __name__ == "__main__":
    unittest.main(verbosity=1)
