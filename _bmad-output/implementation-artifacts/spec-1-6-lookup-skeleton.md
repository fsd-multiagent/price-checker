---
title: 'Lookup skeleton with row order and lowest price'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: 'ddaa957c7318dd3e4b42b7b41b43f77cc01fe382'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md'
warnings: []
deferred:
  - summary: >-
      The packaged backend does not start: AdapterCatalog needs one adapter bean per retailers.yaml id and main code has none until Epic 2 adapters ship.
    evidence: |-
      Epic AC 1.6.1 assumption and epics.md line 945 put the no-op adapters only in tests. Owner decision needed if the demo must boot before Epic 2 (for example, exempt disabled retailers from the check).
    location: >-
      backend/src/main/java/ro/pricechecker/lookup/AdapterCatalog.java
    severity: medium
  - summary: >-
      Test StubAdapters is found by component scan; once real adapter beans exist every Spring test context will report duplicate ids.
    evidence: |-
      StubAdapters is a scanned @Configuration registering one bean per retailers.yaml id; make it opt-in (@Import) when the first real adapter lands.
    location: >-
      backend/src/test/java/ro/pricechecker/lookup/StubAdapters.java
    severity: medium
  - summary: >-
      Outcome normalisation is missing: currency not RON, eanOnPage mismatch, non-positive price, adapter URL outside the retailer domain.
    evidence: |-
      Story 2.8 (AC 2.8.4) owns normalize; until then no adapter is enabled or Approved, so no FOUND row can reach users.
    location: >-
      backend/src/main/java/ro/pricechecker/lookup/LookupService.java
    severity: medium
  - summary: >-
      RetailerHttp.get returns only the body, so adapters cannot tell BLOCKED from HTTP_ERROR or TIMEOUT.
    evidence: |-
      The fetch client stories in Epic 2 define the real client; widen the port then.
    location: >-
      backend/src/main/java/ro/pricechecker/adapter/RetailerHttp.java
    severity: low
  - summary: >-
      AC 1.6.2-1.6.4 are proven at service level; HTTP-level tests use a mocked LookupService, and no Spring test shows startup failing on an id mismatch.
    evidence: |-
      Intent-alignment audit; an end-to-end MockMvc test with a test register and throwing adapter would settle it.
    location: >-
      backend/src/test/java/ro/pricechecker/api/LookupControllerTest.java
    severity: medium
---

<intent-contract>

## Intent

**Problem:** `GET /api/lookup` returns an empty `rows` list; there are no adapter port types, no lookup service, no row order and no Lowest-price marking (story 1.6, AD-4, AD-5, AD-7).

**Approach:** Add the AD-7 port types in `adapter`, a `lookup` service that builds one row per Approved retailer from the registry and adapter beans, orders rows and sets `lowest` (AD-4), checks one-to-one adapter/config ids at startup, and map its domain rows to the generated DTOs in `api`.

## Boundaries & Constraints

**Always:** rows only for `Registry.approvedDomains()` that have a config entry; adapter disabled → `DISABLED` with no adapter call; EAN not in `known-eans.yaml` for that retailer → `NOT_FOUND`, no adapter call; any adapter exception or `Unavailable` → `UNAVAILABLE`; non-FOUND rows carry only id, name, status, `lowest=false`; `fetchedAt` from the injected `Clock` (T-5); tests tagged with AC ids (T-1); package arrows per `ArchitectureRules` (api→lookup/ean/registry; lookup→ean/cache/registry/adapter; adapter→ean).

**Never:** no HTTP client or `RetailerHttp` implementation (Epic 2 `fetch`); no cache, Block, kill switch, deadlines, parallelism or `normalize` (stories 2.x); no retailer domain or selector literal in main code; no edits to `registry`, `docs/source-register.md`, `config/`, `AGENTS.md`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Mixed rows | FOUND 500, FOUND 300, FOUND 300 OUT_OF_STOCK, FOUND no price, NOT_FOUND, UNAVAILABLE, DISABLED | order: 300 (OOS and in-stock by name), 500, no-price, NOT_FOUND, UNAVAILABLE, DISABLED; `lowest` only on in-stock 300 | — |
| Tie | two eligible FOUND 300 | both `lowest=true`, ordered by name | — |
| None eligible | only OOS / no-price / non-FOUND | no row `lowest` | — |
| Adapter throws | enabled, approved, known EAN, adapter throws | `UNAVAILABLE` row, HTTP 200 | exception caught per row |
| Not approved | config entry whose domain is not Approved | no row | — |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/api/LookupController.java` -- today returns `List.of()` rows; inject `lookup.LookupService`, map `LookupResult` to `ResultRow` (generated in `ro.pricechecker.api.generated.model`; non-FOUND rows leave optional fields null).
- `backend/src/main/java/ro/pricechecker/registry/Registry.java` -- read-only; `approvedDomains()`, `retailers()` (`RetailerConfig.domain/enabled()`), `knownEans()`; public no-arg constructor loads the bundled config.
- `backend/src/main/java/ro/pricechecker/registry/KnownEan.java` -- read-only record mapping EAN → retailer → URL.
- `backend/src/test/java/ro/pricechecker/arch/ArchitectureRules.java:27-35` -- allowed package arrows; api may not import `adapter`, so `lookup` exposes its own `RowStatus`/`RowAvailability` enums.
- `backend/src/test/java/ro/pricechecker/AppConfigTest.java`, `registry/ApprovalBypassTest.java` -- `@SpringBootTest` contexts that must still start once the one-to-one check exists.
- `config/retailers.yaml` -- `cel.ro`, `evomag.ro`, both `adapter: disabled`; register marks both `Proposed`, so the live response has zero rows.
- `api/openapi.yaml:63-93` -- `ResultRow` fields.

## Tasks & Acceptance

**Execution:**
- `backend/src/main/java/ro/pricechecker/adapter/` -- add `RetailerAdapter`, sealed `AdapterOutcome` (`Found(PageFacts)`, `NotFound`, `Unavailable(UnavailableReason)`), `UnavailableReason`, `PageFacts(Long priceMinor, String currency, Availability, URI url, String eanOnPage)`, `Availability`, `RetailerHttp` (minimal GET port) -- AD-7 port types.
- `backend/src/main/java/ro/pricechecker/lookup/AdapterCatalog.java` -- `@Component` taking `List<RetailerAdapter>` and `Registry`; throws `IllegalStateException` on duplicate ids or id sets not equal -- AC 1.6.1.
- `backend/src/main/java/ro/pricechecker/lookup/LookupService.java`, `LookupResult`, `LookupRow`, `RowStatus`, `RowAvailability`, `RowOrder` -- build rows, order, set `lowest`; `RetailerHttp` injected as `Optional` (absent → enabled retailers `UNAVAILABLE`) -- AC 1.6.2-1.6.4.
- `backend/src/main/java/ro/pricechecker/api/LookupController.java` -- map domain rows to DTOs -- AC 1.6.4.
- `backend/src/test/java/ro/pricechecker/lookup/StubAdapters.java` -- test-only bean registrar: one no-op adapter per `retailers.yaml` id (read via `new Registry()`), so Spring contexts start -- 1.6.1 assumption.
- tests: `lookup/AdapterCatalogTest`, `lookup/RowOrderTest` (I/O matrix), `lookup/LookupServiceTest`, `api/LookupControllerTest` extension -- tags `AC-1.6.x`.

**Acceptance Criteria:**
- Given adapter beans whose ids differ from `retailers.yaml` ids (missing, extra or duplicate), when the context starts, then startup fails naming the mismatch.
- Given a test register with Approved and non-Approved retailers, when a valid EAN is looked up, then exactly one row per Approved retailer appears, disabled adapters as `DISABLED`.
- Given any outcome including an exception, when `GET /api/lookup` runs, then the status is 200 and non-FOUND rows serialize only the four required fields.

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass
- verdicts: 30 findings — high 0, medium 9, low 9, false 4, maybe-false 0, out-of-scope by epic 8
- findings:
  - `medium` `defer` [blind] Real app fails to start without main adapter beans — epic 1.6.1 assumption mandates test-only stubs; deferred for owner/Epic 2.
  - `medium` `defer` [blind] Scanned StubAdapters will duplicate real adapters later — deferred to first real adapter story.
  - `medium` `reject` [blind] currency not checked — out of scope: story 2.8 normalize (AC 2.8.4).
  - `medium` `reject` [blind] eanOnPage not compared — out of scope: story 2.8.
  - `medium` `reject` [blind] adapter URL not host-checked — out of scope: story 2.8 normalize; no adapter enabled.
  - `medium` `reject` [blind] price <= 0 accepted — out of scope: extract/normalize (C-7) stories 2.x.
  - `low` `reject` [blind] per-row failures not logged — needs a new LookupLog event; retailer logging belongs to the fetch stories.
  - `false` `reject` [blind] Javadoc cites unknown AC-5.4 — AC-5.4 is the PRD id cited by epic AC 1.6.4.
  - `low` `defer` [blind] RetailerHttp too narrow — Epic 2 fetch client.
  - `medium` `patch` [blind] untested LookupService branches (NotFound, url/availability fallback) — added two LookupServiceTest cases.
  - `low` `reject` [blind] live-config empty-rows test replaced by mock — zero rows follows from no Approved retailer; covered by service tests.
  - `low` `reject` [blind] deferred work unrecorded — recorded in frontmatter `deferred` now.
  - `low` `reject` [blind] test nits (unused EAN_B, fixture coupling, FQN Tuple, one shared supplier) — cosmetic.
  - `medium` `defer` [edge] production startup (same root as blind #1).
  - `medium` `patch` [edge] Error/sneaky checked exception escapes per-row catch — safeRow now catches `Exception` (Errors still propagate by design).
  - `medium` `reject` [edge] price <= 0 — story 2.x.
  - `medium` `reject` [edge] non-RON currency — story 2.8.
  - `medium` `reject` [edge] eanOnPage mismatch — story 2.8.
  - `medium` `reject` [edge] off-domain URL — story 2.8.
  - `low` `reject` [edge] null retailerId NPE — loud startup failure is acceptable.
  - `false` `reject` [edge] toDto valueOf throws — every enum name exists in the contract; now pinned by RowEnumMappingTest.
  - `medium` `defer` [edge] spec claim "live app returns zero rows" — wrong; same root as blind #1.
  - `low` `patch` [edge] AC "any outcome" 2xx — covered by the catch broadening above.
  - `medium` `defer` [gap] real-app startup untested — same root as blind #1.
  - `medium` `patch` [gap] enum mapping only partly exercised — added RowEnumMappingTest over all values.
  - `medium` `patch` [gap] NotFound and fallbacks untested — same tests as blind #10.
  - `false` `reject` [gap other] story causes startup problem — duplicate of #1.
  - `false` `reject` [gap other] no RetailerHttp bean means UNAVAILABLE — documented in Design Notes.
  - `medium` `defer` [intent] ACs proven at service level, not HTTP — deferred (end-to-end MockMvc test).
  - `low` `reject` [intent] retailerName is the domain — documented assumption; registry outside touches.

## Design Notes

`retailerName` is the register domain for now: `SourceRegister.Row` does not parse the `Retailer` column and `registry` is outside this story's touches. Missing `RetailerHttp` bean fails closed (UNAVAILABLE) because the real client arrives with `fetch` in Epic 2. With no Approved retailer, the live app returns zero rows, as today.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL (Spotless, compile with `-Werror`, tests, ArchUnit, text scan).

## Auto Run Result

Status: done

**Summary:** AD-7 port types in `adapter`; `lookup.AdapterCatalog` (one-to-one adapter/config check at startup), `lookup.LookupService` (one row per Approved configured retailer: DISABLED, NOT_FOUND without known EAN, UNAVAILABLE on missing HTTP/adapter/exception), `lookup.RowOrder` (AD-4 order and `lowest`); `api.LookupController` maps domain rows to the generated DTOs; Jackson `non_null` keeps non-FOUND rows to four fields.

**Files:** `adapter/{RetailerAdapter,AdapterOutcome,PageFacts,Availability,UnavailableReason,RetailerHttp}.java` (ports); `lookup/{AdapterCatalog,LookupService,LookupResult,LookupRow,RowOrder,RowStatus,RowAvailability}.java`; `api/LookupController.java` (mapping); `application.yaml` (non_null); tests `lookup/{AdapterCatalogTest,RowOrderTest,LookupServiceTest,StubAdapters}`, `registry/TestRegistries`, `api/RowEnumMappingTest`, updated `api/LookupControllerTest`, `api/LookupAcceptanceGapsTest`.

**Review:** 5 patches applied (per-row catch widened to `Exception`; NotFound and URL/availability fallback tests; enum mapping test); 7 deferred (frontmatter `deferred`); rejects as logged above (mostly normalisation owned by story 2.8).

**Follow-up review:** false (no high patched; medium patches were test additions and a one-word catch change, all verified).

**Verification:** `backend/gradlew -p backend check` — BUILD SUCCESSFUL after patches (Spotless, compile -Werror, all tests, ArchUnit, text scan).

**Residual risks:** the packaged backend fails to start until real adapter beans exist (epic assumption, owner may want disabled retailers exempted); `retailerName` equals the domain.
