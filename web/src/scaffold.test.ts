import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const webRoot = resolve(import.meta.dirname, '..');

interface PackageJson {
  engines?: { node?: string };
  scripts?: Record<string, string>;
  dependencies?: Record<string, string>;
  devDependencies?: Record<string, string>;
}

interface PackageLock {
  packages: Record<string, { version?: string }>;
}

const pkg = JSON.parse(readFileSync(resolve(webRoot, 'package.json'), 'utf8')) as PackageJson;
const allDeps = { ...pkg.dependencies, ...pkg.devDependencies };
const EXACT = /^\d+\.\d+\.\d+$/;

describe('AC 1.2.1 scaffold', () => {
  it('AC 1.2.1: pins every dependency to an exact version', () => {
    const loose = Object.entries(allDeps).filter(([, v]) => !EXACT.test(v));
    expect(Object.keys(allDeps).length).toBeGreaterThan(0);
    expect(loose).toEqual([]);
  });

  it('AC 1.2.1: commits a lockfile that matches the pinned versions', () => {
    const lockPath = resolve(webRoot, 'package-lock.json');
    expect(existsSync(lockPath)).toBe(true);
    const lock = JSON.parse(readFileSync(lockPath, 'utf8')) as PackageLock;
    for (const [name, version] of Object.entries(allDeps)) {
      expect(lock.packages[`node_modules/${name}`]?.version, name).toBe(version);
    }
  });

  it('AC 1.2.1: requires Node 24', () => {
    expect(pkg.engines?.node).toBe('>=24 <25');
    expect(readFileSync(resolve(webRoot, '.nvmrc'), 'utf8').trim()).toBe('24');
  });

  it('AC 1.2.1: check runs lint, format check, typecheck and unit tests', () => {
    const scripts = pkg.scripts ?? {};
    for (const name of ['dev', 'build', 'lint', 'format', 'format:check', 'typecheck', 'test']) {
      expect(scripts[name], name).toBeTruthy();
    }
    for (const step of ['lint', 'format:check', 'typecheck', 'test']) {
      expect(scripts.check).toContain(`npm run ${step}`);
    }
  });
});
