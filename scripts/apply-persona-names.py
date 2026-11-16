#!/usr/bin/env python3
"""Apply the project's persona names to the installed BMAD persona skills.

BMAD hard-codes each persona's name in SKILL.md and treats it as something you cannot configure.
A BMAD update reinstalls those files with the default names.
Re-run this script after every BMAD install or update:

    python3 scripts/apply-persona-names.py          # apply
    python3 scripts/apply-persona-names.py --check  # report only; exit code 1 if anything would change

Scope is deliberately narrow: only SKILL.md and customize.toml of the six persona skills.
Knowledge files are not touched. For example, the "_Source: Murat ..." lines in bmad-tea credit the
module's real author and must keep his name.

The overrides in _bmad/custom/<skill>.toml and _bmad/custom/config.toml set the same names, and
survive updates even if this script is not re-run.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKILLS = ROOT / ".claude" / "skills"

# skill directory -> (BMAD default name, project name, position)
PERSONAS = {
    "bmad-agent-analyst": ("Mary", "Pompiliu", "Analyst"),
    "bmad-agent-pm": ("John", "Onofrei", "PM"),
    "bmad-agent-ux-designer": ("Sally", "Safta", "UX Designer"),
    "bmad-agent-architect": ("Winston", "Loredan", "Architect"),
    "bmad-agent-dev": ("Amelia", "Marghioala", "Developer"),
    "bmad-tea": ("Murat", "Eufrosina", "QA"),
}
FILES = ("SKILL.md", "customize.toml")


def main() -> int:
    check_only = "--check" in sys.argv[1:]
    total = 0
    for skill, (old, new, position) in PERSONAS.items():
        for name in FILES:
            path = SKILLS / skill / name
            if not path.is_file():
                print(f"MISSING  {path.relative_to(ROOT)}")
                continue
            text = path.read_text(encoding="utf-8")
            updated, count = re.subn(rf"\b{re.escape(old)}\b", new, text)
            if count:
                total += count
                verb = "WOULD CHANGE" if check_only else "CHANGED"
                print(f"{verb:12} {path.relative_to(ROOT)}: {old} -> {new} ({position}), {count} occurrence(s)")
                if not check_only:
                    path.write_text(updated, encoding="utf-8")
            else:
                print(f"{'OK':12} {path.relative_to(ROOT)}: already {new}")
    print(f"\n{total} replacement(s) {'pending' if check_only else 'applied'}.")
    return 1 if (check_only and total) else 0


if __name__ == "__main__":
    sys.exit(main())
