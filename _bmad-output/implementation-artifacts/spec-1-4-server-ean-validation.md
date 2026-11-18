---
title: 'Story 1.4: Server-side EAN validation and lookup logging'
type: 'feature'
created: '2026-11-18'
status: 'in-progress'
baseline_revision: '6c2cd020fc9a29d133ca0e33eb4de0af13e76315'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/docs/adr/0017-logging-and-privacy.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The contract (story 1.3) declares `GET /api/lookup` with a 400 problem+json for invalid EANs, but no server code validates an EAN or answers the endpoint, and there is no allowlisted application log (S-11, AD-13, AD-14).

**Approach:** Add `ean.Ean` (the AD-13 rule, run against `api/ean-cases.json`), `lookup.LookupLog` (JSON lines on stdout, allowlisted fields only), and an `api` controller implementing the generated `LookupApi` that validates first and maps every rejection (including a missing `ean` parameter) to 400 `application/problem+json` with the `code`, logging `event=ean_rejected`.

## Boundaries & Constraints

**Always:** validation before any other work; `Ean` instances exist only for valid input; logs carry only `ts, level, event, ean, retailer, outcome, durationMs`, and never the raw input, IP or user agent; time from the injected `Clock` (T-5); tests tagged with the AC id (T-1); constructor injection (C-B2); controller holds no business logic (C-B3); `backend/gradlew -p backend check` stays green (Spotless, `-Xlint:all -Werror`, ArchUnit arrows: `api`→`lookup, ean`; `lookup`→`ean`; `ean`→nothing).

**Never:** retailer rows, adapters, fetch, cache or the lookup service (story 1.6); edits to `api/openapi.yaml`, `api/ean-cases.json`, generated code or `AGENTS.md`; contacting any retailer.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| valid | `ean=%205901234123457%09` | 200 `{"ean":"5901234123457","rows":[]}` | none |
| each invalid case in `ean-cases.json` | e.g. `ean=96385075` | 400 `application/problem+json`, `code` = expected | one `ean_rejected` log line, `outcome` = code |
| missing parameter | `/api/lookup` | 400 problem+json `code=EAN_EMPTY` | logged as `ean_rejected` |
| Unicode space padding | ` ` + valid EAN13 + `﻿` | valid, trimmed (same set as JS `trim()`) | none |
| non-ASCII digits | `٤٠٠٦٣٨١٣٣٣٩٣١` | `EAN_NON_DIGIT` | logged |

</intent-contract>

## Code Map

- `api/ean-cases.json` -- shared cases `{"cases":[{name,input,valid,ean|code}]}`; read in tests via `Path.of("../api/ean-cases.json")` and `tools.jackson.databind.json.JsonMapper` (see `backend/src/test/java/ro/pricechecker/api/EanCasesTest.java:25-37`).
- `backend/build/generated/openapi/.../api/generated/LookupApi.java` -- generated: `ResponseEntity<LookupResponse> lookup(@RequestParam(value="ean", required=true) String ean)`; produces `application/json`, `application/problem+json`.
- `.../api/generated/model/Problem.java` -- `new Problem(EanErrorCode)` + fluent `type/title/status/detail/instance`; `EanErrorCode` enum `EAN_EMPTY|EAN_NON_DIGIT|EAN_LENGTH|EAN_CHECK_DIGIT`; `LookupResponse(String ean, List<ResultRow> rows)`.
- `backend/src/test/java/ro/pricechecker/arch/ArchitectureRules.java:31-40` -- allowed arrows; `api` may use `lookup, ean, registry`; `lookup` may use `ean`; `ean` nothing.
- `backend/src/main/java/ro/pricechecker/ClockConfig.java` -- the `Clock` bean.
- `backend/src/main/resources/application.yaml` -- Tomcat access log already off (AD-14).
- Jackson 3 (`tools.jackson.*`) is on the main classpath via `spring-boot-starter-webmvc`.

## Tasks & Acceptance

**Execution:**
- `backend/src/main/java/ro/pricechecker/ean/EanError.java` -- enum of the four codes.
- `backend/src/main/java/ro/pricechecker/ean/InvalidEanException.java` -- unchecked, carries `EanError`; message is the code name only (never the input).
- `backend/src/main/java/ro/pricechecker/ean/Ean.java` -- record `Ean(String value)`; `static Ean parse(String raw)` applies the AD-13 order with JS-`trim()`-compatible whitespace; compact constructor rejects anything not already a valid EAN.
- `backend/src/main/java/ro/pricechecker/lookup/LookupLog.java` -- `@Component`, constructor `(Clock)`; `eanRejected(EanError)` writes one JSON line to `System.out`: `ts` (UTC ISO), `level=INFO`, `event=ean_rejected`, `outcome=<code>`; fields come from a private `Field` enum so nothing else can be written.
- `backend/src/main/java/ro/pricechecker/api/LookupController.java` -- implements `LookupApi`; parses, returns 200 `LookupResponse(ean, [])` (rows arrive in 1.6).
- `backend/src/main/java/ro/pricechecker/api/EanProblemHandler.java` -- `@RestControllerAdvice(assignableTypes = LookupController.class)`; maps `InvalidEanException` and missing `ean` parameter to 400 problem+json `Problem` and calls `LookupLog.eanRejected`.
- `backend/src/test/java/ro/pricechecker/ean/EanTest.java` -- AC 1.4.1: every shared case, plus Unicode whitespace/digit and null cases.
- `backend/src/test/java/ro/pricechecker/api/LookupControllerTest.java` -- AC 1.4.2/1.4.3 via `@WebMvcTest` + `OutputCaptureExtension`.
- `backend/src/test/java/ro/pricechecker/lookup/LookupLogTest.java` -- AC 1.4.3: keys ⊆ allowlist, fixed clock, no input.

**Acceptance Criteria:**
- AC 1.4.1: Given every case in `api/ean-cases.json`, when `Ean.parse` runs, then a valid case yields `Ean` with the expected value and an invalid case throws with the expected code.
- AC 1.4.2: Given `GET /api/lookup?ean=<invalid>` (each invalid shared case, and a missing parameter), when called, then the response is 400 `application/problem+json` with the matching `code`, and no lookup work runs (nothing beyond the controller and the log exists to call; the controller rejects before building a response).
- AC 1.4.3: Given a rejected input, when logged, then exactly one stdout JSON line has `event=ean_rejected` and `outcome=<code>`, its keys are a subset of the allowlist, and neither the raw input nor any IP or user-agent value appears in the output.

## Spec Change Log

## Review Triage Log

## Design Notes

- ADR 0017 says "a test asserts a log line has no other fields", so the error code travels in `outcome` (an enum field) rather than a new `code` key.
- Whitespace set equals ECMAScript `String.prototype.trim()` (Unicode `Zs`, TAB, LF, VT, FF, CR, U+2028, U+2029, U+FEFF) so server and `web/src/ean.ts` (story 1.7) trim identically; this settles the story-1.3 deferral. Digits are ASCII `0-9` only.
- Valid EAN returns an empty `rows` list until story 1.6 adds the lookup service.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: exit 0, new tests pass.
- `git status --short` -- expected: only intended source, test and spec files.
