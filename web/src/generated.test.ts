import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { LookupApi, ResultRowStatusEnum, StatusApi } from './generated';

const webRoot = resolve(import.meta.dirname, '..');

describe('AC 1.3.2 generated API client', () => {
  it('AC 1.3.2: generates LookupApi and StatusApi from api/openapi.yaml', () => {
    expect(typeof LookupApi).toBe('function');
    expect(typeof StatusApi).toBe('function');
    expect(typeof new LookupApi().lookup).toBe('function');
    expect(typeof new StatusApi().status).toBe('function');
  });

  it('AC 1.3.2: generates the ResultRow status enum', () => {
    expect(Object.values(ResultRowStatusEnum)).toEqual([
      'FOUND',
      'NOT_FOUND',
      'UNAVAILABLE',
      'DISABLED',
    ]);
  });

  it('AC 1.3.2: keeps generated code out of git', () => {
    const ignored = readFileSync(resolve(webRoot, '.gitignore'), 'utf8')
      .split('\n')
      .map((line) => line.trim());
    expect(ignored).toContain('src/generated/');
  });
});
