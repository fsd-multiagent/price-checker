import { readdirSync, readFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import type { ConfigEnv, UserConfig } from 'vite';
import { afterEach, describe, expect, it, vi } from 'vitest';
import viteConfig from '../vite.config';
import { DEFAULT_API_PROXY_TARGET, apiProxy } from './devProxy';

const srcRoot = resolve(import.meta.dirname);

function configFor(env: ConfigEnv): UserConfig {
  if (typeof viteConfig !== 'function') throw new Error('vite.config.ts must export a function');
  return viteConfig(env) as UserConfig;
}

const dev: ConfigEnv = { command: 'serve', mode: 'development' };
const preview: ConfigEnv = { command: 'serve', mode: 'production', isPreview: true };
const build: ConfigEnv = { command: 'build', mode: 'production' };

function proxyTargets(config: UserConfig) {
  return {
    server: config.server?.proxy?.['/api'],
    preview: config.preview?.proxy?.['/api'],
  };
}

describe('AC-2 /api dev proxy target', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('AC-2: defaults to http://localhost:8080', () => {
    expect(DEFAULT_API_PROXY_TARGET).toBe('http://localhost:8080');
    expect(apiProxy({})).toEqual({ '/api': { target: 'http://localhost:8080' } });
  });

  it('AC-2: uses VITE_API_PROXY_TARGET when set', () => {
    expect(apiProxy({ VITE_API_PROXY_TARGET: 'http://127.0.0.1:9090' })).toEqual({
      '/api': { target: 'http://127.0.0.1:9090' },
    });
  });

  it('AC-2: falls back to the default when the override is blank', () => {
    for (const blank of ['', '   ']) {
      expect(apiProxy({ VITE_API_PROXY_TARGET: blank })).toEqual({
        '/api': { target: DEFAULT_API_PROXY_TARGET },
      });
    }
  });

  it('AC-1, AC-2: the Vite config proxies /api to the default in dev and preview', () => {
    vi.stubEnv('VITE_API_PROXY_TARGET', '');
    for (const env of [dev, preview]) {
      expect(proxyTargets(configFor(env))).toEqual({
        server: { target: DEFAULT_API_PROXY_TARGET },
        preview: { target: DEFAULT_API_PROXY_TARGET },
      });
    }
  });

  it('AC-2: the Vite config uses the override in dev and preview', () => {
    vi.stubEnv('VITE_API_PROXY_TARGET', 'http://127.0.0.1:9090');
    for (const env of [dev, preview]) {
      expect(proxyTargets(configFor(env))).toEqual({
        server: { target: 'http://127.0.0.1:9090' },
        preview: { target: 'http://127.0.0.1:9090' },
      });
    }
  });
});

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === 'generated' ? [] : sourceFiles(path);
    return /\.(ts|tsx)$/.test(entry.name) ? [path] : [];
  });
}

describe('AC-5 no proxy in the build', () => {
  it('AC-5: no app source except devProxy.ts names the proxy target or imports devProxy', () => {
    const offenders = sourceFiles(srcRoot)
      .filter((path) => !/\.test\.(ts|tsx)$/.test(path))
      .filter((path) => relative(srcRoot, path) !== 'devProxy.ts')
      .filter((path) => {
        const text = readFileSync(path, 'utf8');
        return (
          text.includes('localhost:8080') ||
          text.includes('VITE_API_PROXY_TARGET') ||
          /['"][^'"]*devProxy(\.[jt]s)?['"]/.test(text)
        );
      })
      .map((path) => relative(srcRoot, path));
    expect(offenders).toEqual([]);
  });

  it('AC-5: the build config has no proxy', () => {
    const config = configFor(build);
    expect(config.server?.proxy).toBeUndefined();
    expect(config.preview?.proxy).toBeUndefined();
  });
});
