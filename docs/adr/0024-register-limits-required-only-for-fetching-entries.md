# 0024. Require parseable register Limits only for retailer entries that can fetch

- **Status:** Proposed
- **Date:** 2026-11-18
- **Deciders:** Marghioala (Developer), for review by Loredan (Architect) and the owner
- **Stage / story:** 4-1-adapter-altexro-disabled, run 20261117-174705-full
- **Rules touched:** S-1, S-5, D-2; AC-9.1, AC-9.4, AC-N3.3; epics AC 1.5.2, AC 4.1.2; spine AD-8; amends ADR 0009

## Context

ADR 0009 (and spine AD-8, epics AC 1.5.2) says the register test fails when a `retailers.yaml` entry's register Limits value "cannot be parsed", with no exception. Story 4.1 must ship `altex.ro` in `config/retailers.yaml` with `adapter: disabled` (AC 4.1.2; ADR 0009 Consequences: "The Altex Adapter ships with `enabled: false`"). The register row for Altex is `Not checked` and its Limits cell is `—`, because nobody has checked Altex's terms yet. Agents may not edit `docs/source-register.md` (owner-only), and inventing a Limits value would record a limit nobody approved (S-5). So ADR 0009 as written makes its own Consequence impossible: the Altex entry fails the test until the owner checks Altex.

Limits exist to bound fetching (S-5, AC-N3.3). An entry that is `adapter: disabled` and not Approved never fetches: it gets no Result row and the fetch client refuses non-Approved domains (AD-6, AD-8).

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Require parseable Limits only for entries that are `adapter: enabled` or Approved; a disabled, not Approved entry still needs positive `minIntervalMs` and `concurrency` | Altex can ship disabled as ADR 0009 intends; every entry that can fetch keeps the full check; no register edit | The register test accepts `—` for entries that cannot fetch |
| B. Keep the check; leave Altex out of `retailers.yaml` | No rule change | Breaks AC 4.1.2 and ADR 0009's Consequence; `AdapterCatalog` needs a config entry per adapter bean, so the adapter cannot exist either |
| C. Ask the owner to write a Limits value for Altex in the register | No rule change | The value would be a guess for a site whose terms are unchecked (S-5); blocks the story on an owner edit with no real data |

## Decision

Option A. The register test reports "register Limits cannot be parsed" for an entry that is `adapter: enabled` or whose domain is Approved. For an entry that is `adapter: disabled` and not Approved, an unparseable Limits value is allowed, but `minIntervalMs` and `concurrency` must still be positive. Once Limits parse, the "not looser than the register" check applies to every entry, as before. This amends the last clause of ADR 0009's Decision and the matching clause of AD-8 and AC 1.5.2; the rest of ADR 0009 stands.

## Consequences

- `altex.ro` ships in `config/retailers.yaml` with `adapter: disabled` while its register Limits are `—`.
- Approving a retailer, or enabling its adapter, without a parseable Limits value still fails the test, so the owner must record Limits at approval time.
- `ConfigCheckTest` covers all three cases: Approved with `—` fails, enabled with `—` fails, disabled and not Approved with `—` passes (with positive values).
- No new standard rule; S-1 and S-5 are unchanged.

## Compliance

S-1: unchanged; an enabled entry that is not Approved still fails the test, and the fetch client still refuses non-Approved domains at runtime. S-5: every entry that can fetch (enabled or Approved) must have register Limits that parse and that its own values do not loosen. An entry that cannot fetch has no rate to limit. Veronel (Security & Compliance) should confirm.
