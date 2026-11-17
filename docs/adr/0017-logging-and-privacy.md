# 0017. Log only an allowlist of fields as JSON to stdout, and turn off access logs

- **Status:** Proposed
- **Date:** 2026-11-17
- **Deciders:** Loredan (Architect)
- **Stage / story:** stage 4, run 20261117-174705-full
- **Rules touched:** C-9, S-7, S-9; NFR-5 (AC-N5.2, AC-N5.3), AC-N3.7, AC-11.2; spine AD-14

## Context

Logs may hold the EAN, retailer, outcome and timing, and never an IP address, a client user agent or page content (C-9, S-9, AC-N3.7). Reverse proxies and Tomcat log client IPs by default.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. One `LookupLog` helper writing fixed JSON fields; access logs off in Tomcat and Caddy | Privacy by construction; easy to test | Less debugging data |
| B. Access logs with IP anonymized | More traffic insight | Still client data; easy to misconfigure |

## Decision

Option A. Fields: `ts`, `level`, `event`, `ean`, `retailer`, `outcome`, `durationMs`. `LookupLog` accepts only a validated `Ean`; a rejected input logs `event=ean_rejected` with the error code, never the input. `outcome` is an enum; exceptions log their type, not their message. Caddy has no access log, and its own logs drop `remote_ip`, headers and URI. Blocks log at `WARN` with retailer and time. A test asserts a log line has no other fields.

## Consequences

- No traffic analytics (none wanted: AC-N5.4).
- Debugging relies on outcomes and the Health check report.

## Compliance

Enforces S-9 and C-9 (no IP, no client user agent) and S-7 (no page content in logs).
