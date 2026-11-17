/bmad-architecture -H Create the architecture for price-checker from the PRD and the UX documents. Record every decision as an ADR from `docs/adr/0000-template.md` (D-2).

Run `${run_id}`, scope `${scope}`, stage `${stage}`, step `${step}`, round ${round}.
Your working directory is the worktree `${worktree}`. Write only inside it.

Inputs:
${inputs}

Feedback from the previous round:
${feedback}

When you finish, end your final message with exactly one fenced `json` block:
Do not change the `sdlc-commands` block in `AGENTS.md` (the commands the gates run): the orchestrator puts it back as it is on `main` before it commits, so such edits are lost. If a gate command should change, say so in your result (a `minor` finding, or an assumption) and the owner makes the change through a reviewed PR (ADR 0021).

`{"status": "done" | "blocked" | "ambiguous", "open_questions": [{"question": "...", "blocking": false}], "assumptions": ["..."]}`.
Use `blocked` when you cannot finish, and `ambiguous` (or a question with `"blocking": true`) only
for an open question that a human must answer before the work can continue.
