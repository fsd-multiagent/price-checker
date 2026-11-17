/bmad-build-auto Implement story `${story_key}` (${story_title}) from `${epics_path}`. Commit only on the current branch.

Story sources (the only retailer domains it may contact): ${story_sources}
Story touches: ${story_touches}

Run `${run_id}`, scope `${scope}`, stage `${stage}`, step `${step}`, round ${round}.
Your working directory is the worktree `${worktree}`. Write only inside it.

Inputs:
${inputs}

Feedback from the previous round:
${feedback}

When you finish, end your final message with exactly one fenced `json` block:
`{"status": "done" | "blocked" | "ambiguous", "open_questions": [{"question": "...", "blocking": false}], "assumptions": ["..."]}`.
Use `blocked` when you cannot finish, and `ambiguous` (or a question with `"blocking": true`) only
for an open question that a human must answer before the work can continue.
