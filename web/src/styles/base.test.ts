import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const srcRoot = resolve(import.meta.dirname, '..');
const tokensCss = readFileSync(resolve(import.meta.dirname, 'tokens.css'), 'utf8');
const baseCss = readFileSync(resolve(import.meta.dirname, 'base.css'), 'utf8');
const mainTsx = readFileSync(resolve(srcRoot, 'main.tsx'), 'utf8');

const defined = new Set([...tokensCss.matchAll(/(--[\w-]+)\s*:/g)].map((m) => m[1]!));

function rule(css: string, selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(?:^|\\})\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(css)?.[1] ?? '';
}

describe('AC 1.2.2 tokens are loaded and used', () => {
  it('AC 1.2.2: the app entry loads tokens.css before base.css', () => {
    const tokensAt = mainTsx.indexOf("import './styles/tokens.css'");
    const baseAt = mainTsx.indexOf("import './styles/base.css'");
    expect(tokensAt).toBeGreaterThanOrEqual(0);
    expect(baseAt).toBeGreaterThan(tokensAt);
  });

  it('AC 1.2.2: every var() referenced in any stylesheet is a defined token', () => {
    const cssFiles = readdirSync(srcRoot, { recursive: true, encoding: 'utf8' })
      .filter((f) => f.endsWith('.css'))
      .map((f) => resolve(srcRoot, f));
    const undefinedRefs = cssFiles.flatMap((file) =>
      [...readFileSync(file, 'utf8').matchAll(/var\(\s*(--[\w-]+)/g)]
        .map((m) => m[1]!)
        .filter((name) => !defined.has(name))
        .map((name) => `${file}: ${name}`),
    );
    expect(undefinedRefs).toEqual([]);
  });

  it('AC 1.2.2: body text uses the system-font body token, not a literal font', () => {
    const body = rule(baseCss, 'body');
    expect(body).toMatch(/font-family:\s*var\(--typography-body-font-family\);/);
    expect(body).toMatch(/color:\s*var\(--colors-foreground\);/);
    expect(body).toMatch(/background:\s*var\(--colors-background\);/);
  });

  it('AC 1.2.2: no stylesheet declares a font-family outside the tokens', () => {
    const literal = [...baseCss.matchAll(/font-family:\s*([^;]+);/g)]
      .map((m) => m[1]!.trim())
      .filter((v) => !v.startsWith('var('));
    expect(literal).toEqual([]);
  });
});
