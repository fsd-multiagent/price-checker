/bmad-sprint-planning -H Check implementation readiness of `${epics_path}`.

Run `${run_id}`, scope `${scope}`, stage `${stage}`, step `${step}`, round ${round}.
Your working directory is the worktree `${worktree}`. Write only inside it.

Inputs:
${inputs}

${factory_rules}

Feedback from the previous round:
${feedback}

When you finish, end your final message with exactly one fenced `json` block:
`{"status": "PASS" | "CONCERNS" | "FAIL", "open_questions": [], "assumptions": []}`.
