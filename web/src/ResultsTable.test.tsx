import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { LookupResponse } from './generated';
import { ResultsTable } from './ResultsTable';

const baseCss = readFileSync(resolve(import.meta.dirname, 'styles/base.css'), 'utf8');
const NOW = new Date('2026-11-17T20:00:00Z');

const response: LookupResponse = {
  ean: '5901234123457',
  rows: [
    {
      retailerId: 'b',
      retailerName: 'Beta',
      status: 'FOUND',
      priceMinor: 129999,
      availability: 'IN_STOCK',
      url: 'https://shop.example/b',
      fetchedAt: new Date('2026-11-17T12:05:00Z'),
      lowest: true,
    },
    {
      retailerId: 'a',
      retailerName: 'Alpha',
      status: 'FOUND',
      availability: 'OUT_OF_STOCK',
      url: 'https://shop.example/a',
      fetchedAt: new Date('2026-11-16T08:00:00Z'),
      lowest: false,
    },
    { retailerId: 'c', retailerName: 'Gamma', status: 'NOT_FOUND', lowest: false },
    { retailerId: 'd', retailerName: 'Delta', status: 'UNAVAILABLE', lowest: false },
    { retailerId: 'e', retailerName: 'Epsilon', status: 'DISABLED', lowest: false },
  ],
};

function bodyRows() {
  const [, ...rows] = screen.getAllByRole('row');
  return rows;
}

describe('AC 1.8 results table', () => {
  it('AC 1.8.1: caption, scope="col" headers and rows in server order', () => {
    render(<ResultsTable response={response} now={NOW} />);
    const table = screen.getByRole('table', { name: 'Prices for EAN 5901234123457' });
    const headers = within(table).getAllByRole('columnheader');
    expect(headers.map((h) => h.textContent)).toEqual([
      'Retailer',
      'Price',
      'Availability',
      'Fetched',
      'Link',
    ]);
    headers.forEach((h) => expect(h).toHaveAttribute('scope', 'col'));
    expect(bodyRows().map((r) => r.firstElementChild?.textContent)).toEqual([
      'Beta',
      'Alpha',
      'Gamma',
      'Delta',
      'Epsilon',
    ]);
  });

  it('AC 1.8.2: a FOUND row shows price, availability, Bucharest time and a new-tab link', () => {
    render(<ResultsTable response={response} now={NOW} />);
    const cells = within(bodyRows()[0]!).getAllByRole('cell');
    expect(cells[1]!.textContent?.replace(/\u00a0/g, ' ')).toContain('1.299,99 lei');
    expect(cells[2]).toHaveTextContent('In stock');
    const time = cells[3]!.querySelector('time');
    expect(time).toHaveTextContent('today, 14:05');
    expect(time).toHaveAttribute('datetime', '2026-11-17T12:05:00.000Z');
    const link = within(cells[4]!).getByRole('link', {
      name: 'View at Beta (opens in a new tab)',
    });
    expect(link).toHaveAttribute('href', 'https://shop.example/b');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('AC 1.8.2: a FOUND row without a price says "unavailable" in the Price cell', () => {
    render(<ResultsTable response={response} now={NOW} />);
    const cells = within(bodyRows()[1]!).getAllByRole('cell');
    expect(cells[1]).toHaveTextContent(/^unavailable$/);
    expect(cells[2]).toHaveTextContent('Out of stock');
    expect(cells[3]).toHaveTextContent('16 Nov 2026, 10:00');
  });

  it('AC 1.8.3: non-FOUND rows show muted status and "—"/"not applicable"; no cell is blank', () => {
    render(<ResultsTable response={response} now={NOW} />);
    const expected = ['Not found', 'Unavailable', 'Disabled'];
    bodyRows()
      .slice(2)
      .forEach((row, i) => {
        const cells = within(row).getAllByRole('cell');
        const status = cells[1]!.querySelector('.row-status-text');
        expect(status).toHaveTextContent(expected[i]!);
        for (const cell of cells.slice(2)) {
          expect(cell).toHaveTextContent('—not applicable');
          expect(cell.querySelector('.visually-hidden')).toHaveTextContent('not applicable');
        }
        expect(within(row).queryByRole('link')).toBeNull();
      });
    for (const cell of screen.getAllByRole('cell')) expect(cell.textContent?.trim()).not.toBe('');
    expect(rule('.row-status-text')).toMatch(/color:\s*var\(--colors-muted-foreground\)/);
  });

  it('AC 1.8.4: lowest rows get the lowest class and a "Lowest price" badge', () => {
    const tied: LookupResponse = {
      ...response,
      rows: response.rows.map((r) =>
        r.retailerId === 'a'
          ? { ...r, priceMinor: 129999, availability: 'IN_STOCK', lowest: true }
          : r,
      ),
    };
    render(<ResultsTable response={tied} now={NOW} />);
    const rows = bodyRows();
    expect(rows.map((r) => r.classList.contains('row-lowest'))).toEqual([
      true,
      true,
      false,
      false,
      false,
    ]);
    expect(within(rows[0]!).getByText('Lowest price')).toHaveClass('badge-lowest');
    expect(within(rows[1]!).getByText('Lowest price')).toHaveClass('badge-lowest');
    expect(screen.getAllByText('Lowest price')).toHaveLength(2);
  });

  it('AC 1.8.4: lowest: true on a non-FOUND or unpriced row gets no green and no badge', () => {
    const odd: LookupResponse = {
      ...response,
      rows: response.rows.map((r) => ({ ...r, lowest: r.retailerId !== 'b' })),
    };
    render(<ResultsTable response={odd} now={NOW} />);
    expect(bodyRows().map((r) => r.classList.contains('row-lowest'))).toEqual([
      false,
      false,
      false,
      false,
      false,
    ]);
    expect(screen.queryByText('Lowest price')).toBeNull();
  });

  it('AC 1.8.4: green tokens appear only in the lowest-row and badge rules', () => {
    const offenders = [...baseCss.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
      .filter((m) => m[2]!.includes('--colors-lowest-'))
      .map((m) => m[1]!.trim())
      .filter((selector) => !/^\.(row-lowest|badge-lowest)\b/.test(selector));
    expect(offenders).toEqual([]);
    expect(baseCss).toMatch(/--colors-lowest-surface/);
    expect(baseCss).toMatch(/--colors-lowest-badge\)/);
  });

  it('AC 1.8.6: the table sits in a focusable, labelled scroll region', () => {
    render(<ResultsTable response={response} now={NOW} />);
    const region = screen.getByRole('region', { name: 'Price results' });
    expect(region).toHaveAttribute('tabindex', '0');
    expect(region).toContainElement(screen.getByRole('table'));
    expect(rule('.results-scroll')).toMatch(/overflow-x:\s*auto/);
  });
});

function rule(selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(?:^|\\})\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(baseCss)?.[1] ?? '';
}
