import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  EanErrorCode,
  LookupResponseFromJSON,
  ResultRowAvailabilityEnum,
  StatusResponseFromJSON,
} from './generated';

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

describe('AC 1.3.1 generated models follow the contract', () => {
  it('AC 1.3.1: maps a LookupResponse with integer priceMinor, UTC fetchedAt and lowest', () => {
    const response = LookupResponseFromJSON({
      ean: '5901234123457',
      rows: [
        {
          retailerId: 'r1',
          retailerName: 'Retailer One',
          status: 'FOUND',
          priceMinor: 1299,
          availability: 'IN_STOCK',
          url: 'https://example.invalid/p',
          fetchedAt: '2026-11-18T10:00:00Z',
          lowest: true,
        },
      ],
    });
    expect(response.ean).toBe('5901234123457');
    const row = response.rows[0];
    expect(row).toBeDefined();
    expect(row?.priceMinor).toBe(1299);
    expect(Number.isInteger(row?.priceMinor)).toBe(true);
    expect(row?.fetchedAt).toBeInstanceOf(Date);
    expect(row?.fetchedAt?.toISOString()).toBe('2026-11-18T10:00:00.000Z');
    expect(row?.lowest).toBe(true);
  });

  it('AC 1.3.1: generates the availability and EAN error code enums', () => {
    expect(Object.values(ResultRowAvailabilityEnum)).toEqual([
      'IN_STOCK',
      'OUT_OF_STOCK',
      'UNKNOWN',
    ]);
    expect(Object.values(EanErrorCode)).toEqual([
      'EAN_EMPTY',
      'EAN_NON_DIGIT',
      'EAN_LENGTH',
      'EAN_CHECK_DIGIT',
    ]);
  });

  it('AC 1.3.1: maps a StatusResponse of retailer flags', () => {
    const status = StatusResponseFromJSON({
      retailers: [
        { retailerId: 'r1', retailerName: 'Retailer One', enabled: true, blocked: false },
      ],
    });
    expect(status.retailers).toHaveLength(1);
    expect(status.retailers[0]?.enabled).toBe(true);
    expect(status.retailers[0]?.notBefore).toBeUndefined();
  });
});

describe('AC 1.3.3 shared EAN cases', () => {
  it('AC 1.3.3: the web reads api/ean-cases.json with valid EAN-8, EAN-13 and padded input', () => {
    const valid = eanCases().filter((c) => c.valid);
    const lengths = new Set(valid.map((c) => c.ean?.length));
    expect([...lengths].sort()).toEqual([13, 8].sort());
    expect(valid.some((c) => c.input !== c.input.trim())).toBe(true);
    for (const c of valid) {
      expect(c.ean, c.name).toBe(c.input.trim());
    }
  });

  it('AC 1.3.3: error cases use exactly the EanErrorCode values from the contract', () => {
    const codes = new Set(eanCases().flatMap((c) => (c.valid ? [] : [c.code])));
    expect([...codes].sort()).toEqual(Object.values(EanErrorCode).sort());
  });
});
