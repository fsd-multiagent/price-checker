Story `${story_key}` (${story_title}) has merged. Update the docs, the CLAUDE.md files, the standards, the ADR index and the registers so they match the code. If nothing needs an update, say so in a `minor` finding with the reason (D-5). Never change a register Status cell.

${issue_text}

Run `${run_id}`, scope `${scope}`, stage `${stage}`, step `${step}`, round ${round}.
Your working directory is the worktree `${worktree}`. Write only inside it.

Inputs:
${inputs}

${factory_rules}
The orchestrator also reverts `sdlc/**` on this learn branch (AD-24), after capturing it. When you
recommend a change you did not make, put it in a `minor` finding.

Feedback from the previous round:
${feedback}

Write exactly one JSON verdict file to `${verdict_path}`, in the format of
`docs/standards/review-findings.md`. Every `blocker` must cite a rule ID or an acceptance
criterion ID. Do not write anywhere else under `.sdlc/`.
