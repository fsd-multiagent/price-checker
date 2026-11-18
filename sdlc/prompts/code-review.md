/bmad-code-review Review the diff of story `${story_key}` against `origin/main`, against its acceptance criteria in ${story_source} and the project standards.

${issue_text}

Run `${run_id}`, scope `${scope}`, stage `${stage}`, step `${step}`, round ${round}.
Your working directory is the worktree `${worktree}`. Write only inside it.

Inputs:
${inputs}

${factory_rules}

Feedback from the previous round:
${feedback}

Write exactly one JSON verdict file to `${verdict_path}`, in the format of
`docs/standards/review-findings.md`. Every `blocker` must cite a rule ID or an acceptance
criterion ID. Do not write anywhere else under `.sdlc/`.
