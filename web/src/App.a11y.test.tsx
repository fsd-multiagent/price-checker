// QA expansion for story 1.8 (Eufrosina (QA)): gaps left by the developer tests (T-1, T-5).
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import axe from 'axe-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';

const baseCss = readFileSync(resolve(import.meta.dirname, 'styles/base.css'), 'utf8');
const LABEL = 'EAN (8 or 13 digits)';
const NOW = () => new Date('2026-11-17T20:00:00Z');

/** Server order deliberately not sorted by price: the dearer row comes first (AD-4). */
const unsorted = {
  ean: '5901234123457',
  rows: [
    {
      retailerId: 'z',
      retailerName: 'Zeta',
      status: 'FOUND',
      priceMinor: 250000,
      availability: 'IN_STOCK',
      url: 'https://shop.example/z',
      fetchedAt: '2026-11-17T12:05:00Z',
      lowest: false,
    },
    { retailerId: 'n', retailerName: 'Nu', status: 'NOT_FOUND', lowest: false },
    {
      retailerId: 'a',
      retailerName: 'Alpha',
      status: 'FOUND',
      priceMinor: 99900,
      availability: 'LIMITED',
      url: 'https://shop.example/a',
      fetchedAt: '2026-11-17T12:06:00Z',
      lowest: true,
    },
  ],
};

const allFailed = {
  ean: '5901234123457',
  rows: [
    { retailerId: 'a', retailerName: 'Alpha', status: 'NOT_FOUND', lowest: false },
    { retailerId: 'b', retailerName: 'Beta', status: 'UNAVAILABLE', lowest: false },
  ],
};

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function stubFetch(make: () => Response | Error) {
  const fetchMock = vi.fn(() => {
    const r = make();
    return r instanceof Error ? Promise.reject(r) : Promise.resolve(r);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function submit(value: string) {
  const input = screen.getByLabelText(LABEL);
  input.focus();
  fireEvent.change(input, { target: { value } });
  fireEvent.submit(input);
  return input;
}

async function severeViolations(): Promise<string[]> {
  const results = await axe.run(document.body);
  return results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => v.id);
}

const FOCUSABLE = 'a[href], button, input, select, textarea, [tabindex]';

describe('AC 1.8 QA expansion', () => {
  beforeEach(() => window.history.replaceState(null, '', '/'));
  afterEach(() => {
    vi.unstubAllGlobals();
    window.history.replaceState(null, '', '/');
  });

  it('AC 1.8.1: the page keeps the server row order, not price order', async () => {
    stubFetch(() => json(200, unsorted));
    render(<App now={NOW} />);
    submit('5901234123457');
    const table = await screen.findByRole('table');
    const [, ...rows] = within(table).getAllByRole('row');
    expect(rows.map((r) => r.firstElementChild?.textContent)).toEqual(['Zeta', 'Nu', 'Alpha']);
    expect(rows[2]).toHaveClass('row-lowest');
  });

  it('AC 1.8.5: the loading spinner is hidden from assistive technology', () => {
    window.history.replaceState(null, '', '/?ean=5901234123457');
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>(() => {})),
    );
    render(<App now={NOW} />);
    const spinner = document.querySelector('.status-line .spinner');
    expect(spinner).toHaveAttribute('aria-hidden', 'true');
    expect(document.querySelector('.status-line')).toHaveTextContent(/^Checking retailers…$/);
  });

  it('AC 1.8.5: the all-failed summary counts zero found of every retailer', async () => {
    stubFetch(() => json(200, allFailed));
    render(<App now={NOW} />);
    submit('5901234123457');
    await screen.findByRole('table');
    expect(document.querySelector('.status-line')).toHaveTextContent(
      'Found prices at 0 of 2 retailers.',
    );
    expect(screen.getAllByRole('row')).toHaveLength(3);
  });

  it('AC 1.8.6: no stylesheet rule removes the focus outline', () => {
    expect(baseCss).not.toMatch(/outline:\s*(none|0)\b/);
    expect(baseCss).not.toMatch(/outline-width:\s*0\b/);
    const focusRule = /(^|\})\s*:focus-visible\s*\{([^}]*)\}/.exec(baseCss)?.[2] ?? '';
    expect(focusRule).toMatch(/outline:\s*2px solid/);
  });

  it('AC 1.8.6: in the results state every focusable element is keyboard-reachable', async () => {
    stubFetch(() => json(200, unsorted));
    render(<App now={NOW} />);
    submit('5901234123457');
    await screen.findByRole('table');
    const focusable = [...document.querySelectorAll<HTMLElement>(FOCUSABLE)];
    for (const el of focusable) {
      expect(el.tabIndex).toBeGreaterThanOrEqual(0);
      expect(el).not.toBeDisabled();
      el.focus();
      expect(el).toHaveFocus();
    }
    const names = focusable.map((el) => el.getAttribute('aria-label') ?? el.textContent);
    expect(names).toEqual(
      expect.arrayContaining([
        'Price results',
        'View at Zeta (opens in a new tab)',
        'View at Alpha (opens in a new tab)',
      ]),
    );
  });

  it.each([
    ['all failed', () => json(200, allFailed)],
    ['a request failure', () => new TypeError('Failed to fetch')],
  ])('AC 1.8.6: focus stays in the input after %s', async (_, make) => {
    stubFetch(make);
    render(<App now={NOW} />);
    const input = submit('5901234123457');
    await waitFor(() => expect(document.querySelector('.notice')).not.toBeNull());
    expect(input).toHaveFocus();
  });

  it('AC 1.8.6: axe reports no serious or critical violations in the idle state', async () => {
    render(<App now={NOW} />);
    expect(await severeViolations()).toEqual([]);
  });

  it('AC 1.8.6: axe reports no serious or critical violations with a field error', async () => {
    stubFetch(() => json(200, unsorted));
    render(<App now={NOW} />);
    submit('123');
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(await severeViolations()).toEqual([]);
  });

  it('AC 1.8.6: axe reports no serious or critical violations with the request-failed notice', async () => {
    stubFetch(() => json(500, { title: 'Internal Server Error' }));
    render(<App now={NOW} />);
    submit('5901234123457');
    await screen.findByRole('alert');
    expect(await severeViolations()).toEqual([]);
  });

  it('AC 1.8.6: axe reports no serious or critical violations with a stale table and a field error', async () => {
    stubFetch(() => json(200, unsorted));
    render(<App now={NOW} />);
    submit('5901234123457');
    await screen.findByRole('table');
    submit('12');
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(await severeViolations()).toEqual([]);
  });
});
