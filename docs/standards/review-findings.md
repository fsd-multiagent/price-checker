# Review findings format

Every reviewer and judge writes its verdict in this format, so that Zamolxe (Orchestrator) can check the gate without interpreting prose. This covers Veronel (Security & Compliance), Filofteia (Code Reviewer), `bmad-code-review` and every judge call.

## Severity

| Severity | Meaning | Effect on the gate |
|---|---|---|
| `blocker` | Breaks a rule in `docs/standards/`, breaks an acceptance criterion, or causes data loss, a security problem or a compliance problem | The gate **fails**. The work goes back for a fix round |
| `major` | A real defect or risk that does not break a rule | Logged. It goes to the deferred-work list and does not block |
| `minor` | Style, naming or a suggestion | Logged only |

## Where the verdict goes

Zamolxe (Orchestrator) names the verdict path **in the prompt**. It also sets the environment variable `SDLC_VERDICT_PATH`, but a reviewer never needs a shell to find it.

| Reviewer | How the verdict is delivered |
|---|---|
| Read-only reviewers: Veronel (Security & Compliance), Filofteia (Code Reviewer), judge calls | End the final message with exactly one fenced `json` block holding the verdict. Zamolxe (Orchestrator) parses the last such block and saves it to the verdict path. These reviewers have no write tools |
| Reviewers with write tools: `bmad-code-review`, Aglaia (Knowledge Keeper) | Write exactly one JSON file to the verdict path given in the prompt |

If no valid verdict is found, the gate fails. It never passes by default.

## Verdict JSON

```json
{
  "reviewer": "code-reviewer",
  "target": "story/2-1-ean-input @ <commit-sha>",
  "verdict": "fail",
  "findings": [
    {
      "id": "CR-1",
      "severity": "blocker",
      "rule": "C-7",
      "file": "backend/src/main/java/.../EvomagAdapter.java",
      "line": 42,
      "summary": "Unparseable price is returned as 0",
      "fix": "Return Availability.UNAVAILABLE when the price cannot be parsed"
    }
  ]
}
```

## Rules

- `verdict` is `"fail"` if and only if at least one finding is a `blocker`. Otherwise it is `"pass"`.
- A `blocker` must cite a rule ID (`C-…`, `T-…`, `S-…`, `I-…`, `D-…`) or an acceptance criterion ID. A blocker with no citation is downgraded to `major`.
- Report only what the evidence in the diff or document shows. "Might" and "could" never make a blocker.
- An empty `findings` array with `"verdict": "pass"` is a valid result.
