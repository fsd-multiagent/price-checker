/bmad-testarch-automate -H Add tests for the acceptance criteria of story `${story_key}` in ${story_source}. Test names carry the criterion IDs (T-1).

${issue_text}

Run `${run_id}`, scope `${scope}`, stage `${stage}`, step `${step}`, round ${round}.
Your working directory is the worktree `${worktree}`. Write only inside it.

Inputs:
${inputs}

${factory_rules}

Feedback from the previous round:
${feedback}

When you finish, end your final message with exactly one fenced `json` block:
`{"status": "done" | "blocked" | "ambiguous", "open_questions": [{"question": "...", "blocking": false}], "assumptions": ["..."]}`.
Use `blocked` when you cannot finish, and `ambiguous` (or a question with `"blocking": true`) only
for an open question that a human must answer before the work can continue.
