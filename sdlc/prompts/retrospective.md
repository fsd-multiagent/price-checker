/bmad-retrospective -H Run the retrospective for `${story_key}` using ${story_source}.

${issue_text}

Run `${run_id}`, scope `${scope}`, stage `${stage}`, step `${step}`, round ${round}.
Your working directory is the worktree `${worktree}`. Write only inside it.

Inputs:
${inputs}
- `${worktree}/.sdlc/usage-summary.md`: this epic's agent usage in this run, by agent, stage and
  round, written by Zamolxe (Orchestrator) (AD-24)

Read the usage summary. For each stage where usage looks wasteful or too tight (many rounds,
attempts cut short, high cost for little output), record the change you recommend to that
stage's `model`, `effort` or `max_turns` in `sdlc/pipeline.yaml` as an action item in the
retrospective. You may also make the change in `sdlc/pipeline.yaml`: it becomes an owner-review
proposal, as below, and the orchestrator reverts `sdlc/**` on this branch.

${factory_rules}

Feedback from the previous round:
${feedback}

When you finish, end your final message with exactly one fenced `json` block:
`{"status": "done" | "blocked" | "ambiguous", "open_questions": [{"question": "...", "blocking": false}], "assumptions": ["..."]}`.
Use `blocked` when you cannot finish, and `ambiguous` (or a question with `"blocking": true`) only
for an open question that a human must answer before the work can continue.
