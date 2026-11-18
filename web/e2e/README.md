# web/e2e

Playwright end-to-end tests of UJ-1 (compare prices) and UJ-2 (one retailer fails), story 2.14 (T-4).

## Run (from the repo root)

```sh
npm --prefix web ci
npm --prefix web/e2e ci
npm --prefix web/e2e exec -- playwright install chromium   # once
npm --prefix web/e2e test
```

Needs Node 24 and a JDK (Gradle provisions JDK 21). Playwright starts two servers itself:

- the e2e backend, `backend/gradlew -p backend bootTestRun`, which runs
  `ro.pricechecker.e2e.E2eServer` from the backend test sources on port `E2E_BACKEND_PORT`
  (default 18080). It is the real app with the register, retailers and known EANs from
  `backend/src/test/resources/e2e/` and the `FixtureHttp` stub as the only retailer HTTP: it serves
  `backend/src/test/resources/e2e/pages/<id>/<ean>.html`, hangs (60 s, `E2eBeans.HANG`) for a known URL
  without a page and throws for any other URL, so no request leaves the machine (T-3). Context
  shutdown ends any hang still in progress. This holds only while `FixtureHttp` is the only
  `RetailerHttp` bean: when story 2.8 wires a `FetchClient` bean, `E2eBeans` must override it
  explicitly (`E2eJourneysApiTest` checks there is exactly one).
  `GET /e2e/requests?ean=` returns the stub's page-request count for one EAN.
- the Vite dev server for `web/` with `e2e/vite.config.ts` (proxies `/api` to the backend) on
  port `E2E_WEB_PORT` (default 5174). It runs `npm run generate` first.

Outside CI, already running servers on those ports are reused. Results and the HTML report go to
`node_modules/.e2e/`.

## Tests

| EAN             | Test                                                        | Stub               |
| --------------- | ----------------------------------------------------------- | ------------------ |
| `5940000000011` | 2.14.1 UJ-1: two FOUND rows, the cheaper one "Lowest price" | both pages         |
| `5940000000028` | 2.14.2 UJ-2: one row Unavailable after its 1500 ms deadline | one retailer hangs |
| `5940000000035` | 2.14.3 second Lookup from the cache, no new page request    | both pages         |

Each test has its own EAN, so order does not matter (T-5). Dependencies are pinned and the
lockfile is committed (S-12).
