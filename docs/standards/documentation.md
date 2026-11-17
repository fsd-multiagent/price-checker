# Documentation standard

- **Followed by:** Aglaia (Knowledge Keeper), and every agent that writes documents
- **Verified by:** the stage 9 gate in Zamolxe (Orchestrator)

- **D-1** After each merged story, update every document the change affects: the root and folder `CLAUDE.md` files (commands, layout, pitfalls), `docs/bmad-reference.md` (names and paths), and the standards (a new convention gets a new rule ID).
- **D-2** Every architecture decision that is made or changed gets an ADR in `docs/adr/NNNN-<slug>.md`, written from the template. Superseded ADRs are marked as superseded, never deleted.
- **D-3** When a retailer, control or piece of evidence changes, update `docs/source-register.md` and `docs/compliance-register.md`.
- **D-4** Planning artifacts never go stale. If an implementation departs from the PRD or the architecture, either update the artifact in the same PR or escalate for `bmad-correct-course`.
- **D-5** If a merge needs no documentation change, record "no update needed" with a one-line reason in the stage 9 verdict.
- **D-6** Refer to agents as Name (Position), for example "Marghioala (Developer)".
- **D-7** Use English, keep sentences short, and use tables for anything that is compared or looked up.
