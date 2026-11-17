import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const webRoot = resolve(import.meta.dirname, '../..');
const designPath = resolve(
  webRoot,
  '../_bmad-output/planning-artifacts/ux-designs/ux-price-checker-2026-11-17/DESIGN.md',
);
const srcRoot = resolve(webRoot, 'src');
const tokensCss = readFileSync(resolve(import.meta.dirname, 'tokens.css'), 'utf8');

const TOKEN_GROUPS = ['colors', 'typography', 'rounded', 'spacing'];

const kebab = (s: string) => s.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();

function scalar(raw: string): string {
  const v = raw.trim();
  const quote = v[0];
  if (quote === "'" || quote === '"') {
    return v.slice(1, v.indexOf(quote, 1));
  }
  return v.split(/\s+#/)[0]!.trim();
}

/** Reads the token groups from DESIGN.md frontmatter as `--group-name[-prop]` -> value. */
function designTokens(): Map<string, string> {
  const front = readFileSync(designPath, 'utf8').split(/^---$/m)[1] ?? '';
  const tokens = new Map<string, string>();
  let group: string | null = null;
  let sub: string | null = null;
  for (const line of front.split('\n')) {
    if (/^\s*(#.*)?$/.test(line)) continue;
    const m = /^( *)([\w-]+):\s*(.*)$/.exec(line);
    if (!m) continue;
    const [, indent, key, rest] = m as unknown as [string, string, string, string];
    if (indent.length === 0) {
      group = TOKEN_GROUPS.includes(key) ? key : null;
      sub = null;
    } else if (group && indent.length === 2) {
      if (rest === '') sub = key;
      else tokens.set(`--${group}-${key}`, scalar(rest));
    } else if (group && sub && indent.length === 4) {
      tokens.set(`--${group}-${sub}-${kebab(key)}`, scalar(rest));
    }
  }
  return tokens;
}

/** Normalizes a DESIGN.md value to the form expected in tokens.css. */
function expected(value: string): string {
  const ref = /^\{([\w-]+)\.([\w-]+)(?:\.([\w-]+))?\}$/.exec(value);
  if (ref) {
    const name = [ref[1], ref[2], ref[3]].filter(Boolean).map((p) => kebab(p!));
    return `var(--${name.join('-')})`;
  }
  return value.replace(/"/g, "'").toLowerCase();
}

function cssVars(css: string): Map<string, string> {
  const vars = new Map<string, string>();
  for (const m of css.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
    vars.set(m[1]!, m[2]!.trim().replace(/"/g, "'").toLowerCase());
  }
  return vars;
}

describe('AC 1.2.2 design tokens', () => {
  const tokens = designTokens();
  const defined = cssVars(tokensCss);

  it('AC 1.2.2: reads tokens from every DESIGN.md group', () => {
    for (const group of TOKEN_GROUPS) {
      expect(
        [...tokens.keys()].some((k) => k.startsWith(`--${group}-`)),
        group,
      ).toBe(true);
    }
  });

  it('AC 1.2.2: defines every DESIGN.md token with the DESIGN.md value', () => {
    const mismatches = [...tokens].filter(([name, value]) => defined.get(name) !== expected(value));
    expect(mismatches).toEqual([]);
  });

  it('AC 1.2.2: defines no token that DESIGN.md does not have', () => {
    expect([...defined.keys()].filter((name) => !tokens.has(name))).toEqual([]);
  });

  it('AC 1.2.2: font stacks are system fonts', () => {
    const families = [...defined]
      .filter(([name]) => name.endsWith('-font-family'))
      .map(([, v]) => v)
      .filter((v) => !v.startsWith('var('));
    expect(families.length).toBeGreaterThan(0);
    for (const stack of families) {
      expect(stack).toMatch(/(system-ui|ui-monospace)/);
      expect(stack).toMatch(/(sans-serif|monospace)$/);
    }
  });

  it('AC 1.2.2: CSS and index.html load nothing external', () => {
    const files = [
      resolve(webRoot, 'index.html'),
      ...readdirSync(srcRoot, { recursive: true, encoding: 'utf8' })
        .filter((f) => f.endsWith('.css'))
        .map((f) => resolve(srcRoot, f)),
    ];
    expect(files.length).toBeGreaterThan(1);
    for (const file of files) {
      const text = readFileSync(file, 'utf8');
      expect(text, file).not.toMatch(/@font-face/i);
      expect(text, file).not.toMatch(/@import/i);
      expect(text, file).not.toMatch(/https?:\/\//i);
      expect(text, file).not.toMatch(/(url\(|src=|href=)\s*['"]?\/\//i);
    }
  });
});
