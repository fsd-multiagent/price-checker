# 0009. Bundle the source register as the compliance authority, and keep retailer config in three files with one writer each

- **Status:** Proposed
- **Date:** 2026-11-17
- **Deciders:** Loredan (Architect)
- **Stage / story:** stage 4, run 20261117-174705-full
- **Rules touched:** S-1, S-5, S-8, I-9; FR-7, FR-9, FR-11, AC-2.1, AC-7.1 to 7.5, AC-9.1 to 9.4, AC-11.1, AC-N3.3; spine AD-8, AD-9

## Context

AC-9.3 forbids any flag or variable that lets a Lookup contact a non-Approved retailer, yet the Kill switch must change without a deploy (S-8). A Block must survive until an operator clears it (AC-11.1), so a restart must not clear it. The CI check must read the register itself, not a copy (PRD addendum). Rate limits come from the register (AC-N3.3).

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Register bundled into the image and parsed; static `retailers.yaml`; mounted `runtime.yaml` (operator); mounted `blocks.json` (backend) | Approval cannot change at runtime; each file has one writer; Blocks survive restarts | Approval or retirement needs a deploy (Kill switch covers urgent stops) |
| B. One mounted config file for everything | One file | A runtime edit could enable a non-Approved retailer; app and operator both write it |
| C. CI check only, no runtime register check | Simple | A config mistake on the VM reaches a retailer |

## Decision

Option A. `registry` parses the bundled `docs/source-register.md` (`Domain`, `Status`); only `Approved YYYY-MM-DD` counts; a parse error approves nothing. Rows = retailers in `config/retailers.yaml` that are Approved (`adapter: disabled` gives a `DISABLED` row). `runtime.yaml` holds `fetching: allowed|stopped` and `clearGeneration` per retailer, written only by the `kill-switch` workflow (temp file then `mv`) and polled every 15 s; a missing retailer is stopped; a bad file keeps the last good settings. `blocks.json` holds `blockGeneration`, `blockedAt` and reason, owned only by `registry.BlockStore` (whole-map atomic write). A Block is active while `blockGeneration >= clearGeneration`; `clear-block` increments `clearGeneration`. Integer generations avoid clock skew between the workflow and the backend. A backend test fails when an `adapter: enabled` entry is not Approved, an Approved retailer has no entry, `resolution` is missing or unknown, or a rate limit is looser than the register.

## Consequences

- The Altex Adapter ships with `enabled: false` (AC-9.4); turning it on before approval fails the test.
- The operator never edits files by SSH; the `kill-switch` workflow does it (ADR 0015).
- The 15 s poll keeps AC-7.2 (60 s) with margin.

## Compliance

S-1 is enforced twice: by the CI test and by the fetch client at runtime, both from the register itself. S-8 is met without a deploy. S-6 Blocks persist until a human clears them.
