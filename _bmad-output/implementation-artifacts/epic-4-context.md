# Epic 4 Context: Demo readiness and the compliance gate

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the live demo safe and convincing. The presenter must be able to show three things: an Altex Adapter that already exists, is tested offline and ships disabled behind the source-register gate (compliance gate); a simulated retailer layout change that both the adapter tests and the Health check catch, which is the trigger for the self-healing story; and a demo EAN list that is proven to compare at least two Approved retailers. The epic adds no new runtime behaviour. It adds fixtures, tests and config that show the existing guarantees in front of an audience.

## Stories

- Story 4.1: Disabled Altex Adapter from synthetic fixtures
- Story 4.2: Simulated layout change fails tests and the Health check
- Story 4.3: Demo EAN list check

## Requirements & Constraints

- **Compliance gate:** an Adapter may exist for a retailer that is not Approved, but only if it is built from saved fixture pages. It ships `adapter: disabled`, its tests never contact the retailer, and CI (the register test) fails if it is enabled while the retailer is not Approved. A non-Approved retailer is never contacted and gets no Result row. No flag, env var or runtime file can bypass this. No story in this epic fetches from altex.ro or any other retailer (`sources: []` everywhere).
- **Simulated layout change:** a saved product page with its structured data **and** selectors altered must make the adapter's tests fail and make the Health check fail. Failure output must name the retailer, the EAN and the missing fact. The Health check exits non-zero and writes a report (retailer, EAN, missing fact, `blocked` flag).
- **CI stays green:** altered fixtures are used only by tests that expect the failure, so normal CI passes.
- **Demo list:** `config/known-eans.yaml` must have at least one EAN listed for two or more Approved retailers, and every Approved retailer must have at least two Known EANs. A test checks both.
- **Health check rules:** it obeys the same rules as a Lookup: Approved and enabled retailers only, rate and concurrency limits, and the honest user agent. It checks that every Known EAN is `FOUND` with a price, an availability and a URL.
- **Test traceability:** test names or tags carry the AC id (for example `@Tag("AC-8.4")`).

## Technical Decisions

- **Adapter contract:** one bean per retailer implements `RetailerAdapter.fetchAndExtract(Ean, URI, RetailerHttp)` and returns the sealed `AdapterOutcome` (`Found(PageFacts)`, `NotFound`, `Unavailable(reason)`). `PageFacts` holds only price (bani, nullable), currency, availability, URL and `eanOnPage`, never a name, description or image. Extraction order: structured data, then meta tags, then the adapter's own selectors. A page with no recognizable product facts gives `Unavailable(UNPARSEABLE)`. Adapters do not log, cache, handle deadlines or read config.
- **One-to-one wiring:** the app refuses to start unless the `retailers.yaml` ids and the adapter beans match one-to-one. A new Altex entry needs its adapter bean in the same change.
- **Register authority:** the bundled `docs/source-register.md` is the only compliance authority. `registry.ConfigCheck` runs in tests and at startup: enabled implies Approved, every Approved retailer has an entry, resolution is valid, limits are no looser than the register's, and `known-eans.yaml` holds valid EANs on https URLs on the retailer's host with at least two distinct EANs per Approved retailer.
- **Resolution:** every retailer uses `resolution: curated`. `config/known-eans.yaml` is the curated map, the Health check list and the demo list.
- **Health profile:** the backend runs in the `health` profile with no web server, a no-op cache and a fresh `blocks.json`. It uses the same `lookup` core, register and fetch client, with rate intervals doubled and concurrency 1. To simulate a layout change, run it against a stub that serves the altered page. Live production pages cannot be altered.
- **Fixtures:** they live under `fixtures/<retailer-id>/` (the id is the register domain, for example `fixtures/altex.ro/`). Adapter tests read pages only from there through a fake `RetailerHttp`. A fetched page is saved only after `fetch.PageStripper`. Hand-written synthetic pages need no fetch.
- **Naming:** the Java package is the domain without dots (`retailer.altexro`). Main code must not contain retailer domain or selector string literals; the text-scan test fails the build.

## Cross-Story Dependencies

- 4.1 depends on the lookup skeleton (1.6) and the extract helpers (2.1). It touches `config/retailers.yaml`.
- 4.2 depends on the cel.ro (2.10) and evoMAG (2.11) adapters and the health profile (3.5). It touches only `fixtures/` and `backend/src/test/`.
- 4.3 depends on 2.12 and 2.13, which record real fixtures and curate Known EANs. Those stories wait on the owner approving cel.ro and evomag.ro in the source register, so 4.3 is blocked until then.
- The SM-2 fallback: if the owner does not approve Altex (its affiliate terms may forbid automated access), another Not checked or Proposed retailer goes through the gate live instead.
