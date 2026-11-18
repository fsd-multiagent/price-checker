import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { EanErrorCode } from './generated';
import { eanErrorMessage, isEanErrorCode, validateEan } from './ean';

const repoRoot = resolve(import.meta.dirname, '..', '..');

interface EanCase {
  name: string;
  input: string;
  valid: boolean;
  ean?: string;
  code?: string;
}

function eanCases(): EanCase[] {
  const raw = readFileSync(resolve(repoRoot, 'api', 'ean-cases.json'), 'utf8');
  const parsed = JSON.parse(raw) as { cases: EanCase[] };
  return parsed.cases;
}

describe('AC 1.7.1 validateEan follows api/ean-cases.json', () => {
  it.each(eanCases().map((c) => [c.name, c] as const))('AC 1.7.1: %s', (_name, c) => {
    const result = validateEan(c.input);
    if (c.valid) {
      expect(result).toEqual({ valid: true, ean: c.ean });
    } else {
      expect(result).toEqual({ valid: false, code: c.code });
    }
  });
});

describe('AC 1.7.3 error copy per code', () => {
  it('AC 1.7.3: empty input', () => {
    expect(eanErrorMessage(EanErrorCode.EanEmpty, '   ')).toBe('Error: enter an EAN.');
  });

  it('AC 1.7.3: non-digit input', () => {
    expect(eanErrorMessage(EanErrorCode.EanNonDigit, '4006 381333931')).toBe(
      'Error: an EAN contains only digits.',
    );
  });

  it('AC 1.7.3: wrong length counts the trimmed digits', () => {
    expect(eanErrorMessage(EanErrorCode.EanLength, ' 1234567 ')).toBe(
      'Error: an EAN has 8 or 13 digits. You entered 7.',
    );
  });

  it('AC 1.7.3: bad check digit', () => {
    expect(eanErrorMessage(EanErrorCode.EanCheckDigit, '4006381333932')).toBe(
      'Error: the last digit does not match. Check the EAN for a typo.',
    );
  });

  it('AC 1.7.4: only contract codes are recognised as EAN error codes', () => {
    for (const code of Object.values(EanErrorCode)) {
      expect(isEanErrorCode(code)).toBe(true);
    }
    expect(isEanErrorCode('SOMETHING_ELSE')).toBe(false);
    expect(isEanErrorCode(undefined)).toBe(false);
  });
});
