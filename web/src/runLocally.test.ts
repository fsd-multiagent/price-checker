// @vitest-environment node
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { createServer as createHttpServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { build, createServer, type ViteDevServer } from 'vite';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { validateEan } from './ean';

// Story 0-90 (issue #90): the local demo. Every server here binds 127.0.0.1 on a free port;
// no retailer and no real backend is contacted (T-3).

const webRoot = resolve(import.meta.dirname, '..');
const repoRoot = resolve(webRoot, '..');
const configFile = join(webRoot, 'vite.config.ts');

interface StubBackend {
  url: string;
  requests: string[];
  close: () => Promise<void>;
}

/** A stand-in backend that records each request path and answers a Lookup-shaped JSON body. */
async function startStubBackend(): Promise<StubBackend> {
  const requests: string[] = [];
  const server: Server = createHttpServer((req, res) => {
    requests.push(req.url ?? '');
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ean: '5901234123457', rows: [] }));
  });
  await new Promise<void>((done) => server.listen(0, '127.0.0.1', done));
  const { port } = server.address() as AddressInfo;
  return {
    url: `http://127.0.0.1:${port}`,
    requests,
    close: () => new Promise<void>((done) => server.close(() => done())),
  };
}

describe('AC-1 a lookup reaches /api/lookup through the Vite dev proxy', () => {
  let backend: StubBackend | undefined;
  let vite: ViteDevServer | undefined;

  afterEach(async () => {
    await vite?.close();
    await backend?.close();
    vite = undefined;
    backend = undefined;
    vi.unstubAllEnvs();
  });

  it('AC-1: GET /api/lookup on the dev server is answered by the backend', async () => {
    backend = await startStubBackend();
    vi.stubEnv('VITE_API_PROXY_TARGET', backend.url);
    vite = await createServer({
      configFile,
      root: webRoot,
      mode: 'development',
      logLevel: 'silent',
      server: { host: '127.0.0.1', port: 0, strictPort: false, hmr: false, watch: null },
    });
    await vite.listen();
    const { port } = vite.httpServer!.address() as AddressInfo;

    const response = await fetch(`http://127.0.0.1:${port}/api/lookup?ean=5901234123457`);

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ean: '5901234123457', rows: [] });
    expect(backend.requests).toEqual(['/api/lookup?ean=5901234123457']);
  }, 30_000);
});

function readmeRunLocally(): string {
  const readme = readFileSync(join(repoRoot, 'README.md'), 'utf8');
  const start = readme.search(/^## Run locally\s*$/m);
  expect(start, 'README.md has a "## Run locally" heading').toBeGreaterThanOrEqual(0);
  const rest = readme.slice(start + 1);
  const next = rest.search(/^## /m);
  return next < 0 ? rest : rest.slice(0, next);
}

describe('AC-4 README Run locally section', () => {
  it('AC-4: gives the backend command with local runtime and Block files', () => {
    const section = readmeRunLocally();
    expect(section).toContain('backend/gradlew -p backend bootRun');
    expect(section).toMatch(/PRICE_CHECKER_RUNTIME_FILE=\S+/);
    expect(section).toMatch(/PRICE_CHECKER_BLOCKS_FILE=\S+/);
    expect(section).toContain('config/runtime.local.example.yaml');
  });

  it('AC-4: gives the web dev command', () => {
    expect(readmeRunLocally()).toContain('npm --prefix web run dev');
  });

  it('AC-4: gives the URL to open, on the Vite dev port', () => {
    expect(readmeRunLocally()).toMatch(/Open `http:\/\/localhost:5173`/);
  });

  it('AC-4: gives an example EAN that the web validator accepts', () => {
    const section = readmeRunLocally();
    const match = /example EAN `(\d+)`/.exec(section);
    const ean = match?.[1] ?? '';
    expect(ean, 'README names an example EAN').not.toBe('');
    expect(validateEan(ean)).toEqual({ valid: true, ean });
  });
});

function filesUnder(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    return entry.isDirectory() ? filesUnder(path) : [path];
  });
}

describe('AC-5 the production build has no proxy and no backend URL', () => {
  let outDir: string | undefined;

  afterEach(() => {
    if (outDir) rmSync(outDir, { recursive: true, force: true });
    outDir = undefined;
    vi.unstubAllEnvs();
  });

  it('AC-5: vite build output names no backend target, even with the override set', async () => {
    const sentinel = 'http://proxy-target-sentinel.example:9191';
    vi.stubEnv('VITE_API_PROXY_TARGET', sentinel);
    outDir = mkdtempSync(join(tmpdir(), 'price-checker-ac5-'));
    await build({
      configFile,
      root: webRoot,
      mode: 'production',
      logLevel: 'silent',
      build: { outDir, emptyOutDir: true },
    });

    const files = filesUnder(outDir);
    expect(files.some((path) => path.endsWith('.js'))).toBe(true);
    const offenders = files.filter((path) => {
      const text = readFileSync(path, 'utf8');
      return (
        text.includes('localhost:8080') ||
        text.includes('proxy-target-sentinel') ||
        text.includes('VITE_API_PROXY_TARGET')
      );
    });
    expect(offenders).toEqual([]);
  }, 60_000);
});
