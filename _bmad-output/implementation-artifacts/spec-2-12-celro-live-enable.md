---
status: done
---

# Story 2.12: Record cel.ro fixtures, curate Known EANs and enable the Adapter

## Intent

As a Shopper, I want real cel.ro prices for the Known EANs, so that the live demo shows a real
comparison (RC-2). Source: `_bmad-output/planning-artifacts/epics.md` story 2.12.

## Starting state

Owner PR #100 (commit 67a3b08e3cd3644a16f6823fff2f00d3742444be) had already approved cel.ro in
the register, set `adapter: enabled` in `config/retailers.yaml` and curated two cel.ro Known
EANs. AC 2.12.3 was met by that PR (`CelroAdapterTest.ac_2_10_3_celroEnabledInRetailersYamlOnlyBecauseApproved`).

## Code Map

- `config/known-eans.yaml`: both cel.ro URLs replaced by their `301` targets.
- `fixtures/cel.ro/recorded-5099206027282.html`, `recorded-0740617261219.html`: recorded pages
  (fetched through `FetchClient`, stripped by `PageStripper`, cut to fact elements).
- `fixtures/cel.ro/*.html` (synthetic): stock element `.stoc_produs` -> `#info_stoc`.
- `fixtures/cel.ro/README.md`: recording procedure and findings.
- `backend/src/main/java/ro/pricechecker/retailer/celro/CelroAdapter.java`: stock selector
  `#info_stoc`; currency word also read from the sibling `#product-price ~ .moneda`.
- `backend/src/test/java/ro/pricechecker/retailer/celro/CelroRecordedPagesTest.java`: AC 2.12.1
  and AC 2.12.2 against the recorded pages and `known-eans.yaml`.
- `backend/src/test/java/ro/pricechecker/retailer/celro/CelroAdapterTest.java`: inline stock
  selector updated.

## Design Notes

- Recording used a temporary, environment-gated JUnit class (deleted before commit) that built
  the production `FetchClient` (real `Registry`, `RuntimeSwitch` over a `temp/` `runtime.yaml`
  with `fetching: allowed`, `BlockStore` under `temp/`, `JdkTransport`). Requests made:
  `robots.txt` once, then the four product URLs over two runs (two of them `301`s), then the two
  canonical URLs: all at the configured 1 request / 2 s. No raw page was written to disk.
- `PageStripper` output is about 85 KB per page (whole site layout); the existing facts-only
  fixture test (`CelroAdapterCoverageTest`, < 2 000 chars, no `href`/`src`/`description`) needs
  excerpts, so only the fact elements were kept verbatim.
- Real cel.ro pages carry no EAN or GTIN anywhere (register row says "EAN shown on the product
  page [V]"); `eanOnPage` is `null` and the row stays `FOUND` per AD-11 normalization.
- Synthetic edge-case fixtures stay: real pages cannot show unparseable prices or meta-only
  layouts on demand, and `product-jsonld.html`/`layout-changed.html` are shared with the
  cross-retailer layout-change tests.

## Tasks & Acceptance

- [x] AC 2.12.1: real pages recorded through `FetchClient` and `PageStripper`; adapter tests pass
  against them (`CelroRecordedPagesTest`).
- [x] AC 2.12.2: two cel.ro `{ ean, url }` entries, each giving FOUND with price, availability
  and link (`ac_2_12_2_everyCelroKnownEanHasARecordedFoundPage`).
- [x] AC 2.12.3: met by owner PR #100; test unchanged and passing.
- [x] `backend/gradlew -p backend check` passes.

## Auto Run Result

Status: done
Blocking condition: none

The in-skill review pass was not run as a separate subagent loop; the pipeline's review stage
(Filofteia (Code Reviewer), Veronel (Security & Compliance)) reviews the diff next.

Follow-ups for others:
- `docs/source-register.md` cel.ro row says the EAN is shown on the page; recorded pages show
  none (owner-only file).
- `AGENTS.md` still names `.stoc_produs` and calls the cel.ro fixtures synthetic; Aglaia
  (Knowledge Keeper) should update it after merge (story branches revert `AGENTS.md`).
