# price-checker
Price Checker - sample app for multi-agent study

## Run locally

You need JDK 21 support for Gradle (it provisions JDK 21 through the toolchain), Node 24 and
Java 11 or newer on PATH for the web API client generator. Run every command from the repo root.

1. Copy the example Kill switch file. It stops every retailer, so a local run contacts no
   retailer (AD-9). `temp/` is ignored by git.

   ```sh
   mkdir -p temp
   cp config/runtime.local.example.yaml temp/runtime.yaml
   ```

2. Start the backend on `http://localhost:8080`, with its runtime and Block files under `temp/`:

   ```sh
   PRICE_CHECKER_RUNTIME_FILE="$PWD/temp/runtime.yaml" \
   PRICE_CHECKER_BLOCKS_FILE="$PWD/temp/blocks.json" \
   backend/gradlew -p backend bootRun
   ```

3. In a second terminal, install and start the web dev server:

   ```sh
   npm --prefix web ci
   npm --prefix web run generate
   npm --prefix web run dev
   ```

   The dev server (and `vite preview`) proxies `/api` to `http://localhost:8080`. To use another
   backend, set `VITE_API_PROXY_TARGET`, for example
   `VITE_API_PROXY_TARGET=http://127.0.0.1:9090 npm --prefix web run dev`. A production build
   has no proxy; Caddy routes `/api` there.

4. Open `http://localhost:5173` and look up the example EAN `5901234123457`.

   Expected result: the lookup succeeds (HTTP 200) through the proxy. While no retailer in
   `docs/source-register.md` is Approved the response has no rows; an Approved retailer shows a
   `DISABLED` row while its `fetching` is `stopped`. To check the proxy without a browser:

   ```sh
   curl -s 'http://localhost:5173/api/lookup?ean=5901234123457'
   ```

   It prints the backend's JSON, for example `{"ean":"5901234123457","rows":[]}`.

To fetch from a retailer locally, set `fetching: allowed` for it in `temp/runtime.yaml`, only for
a domain that `docs/source-register.md` marks Approved; the backend re-reads the file within 15 s.
