import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import axe from 'axe-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';

const baseCss = readFileSync(resolve(import.meta.dirname, 'styles/base.css'), 'utf8');
const LABEL = 'EAN (8 or 13 digits)';
const NOW = () => new Date('2026-11-17T20:00:00Z');
const LOADING = 'Checking retailers…';
const ALL_FAILED =
  'No prices found for this EAN. Retailers that could not be reached are marked Unavailable; try again in a minute.';
const REQUEST_FAILED = 'Could not reach Price Checker. Check your connection and try again.';

const twoRows = {
  ean: '5901234123457',
  rows: [
    {
      retailerId: 'a',
      retailerName: 'Alpha',
      status: 'FOUND',
      priceMinor: 129999,
      availability: 'IN_STOCK',
      url: 'https://shop.example/a',
      fetchedAt: '2026-11-17T12:05:00Z',
      lowest: true,
    },
    { retailerId: 'b', retailerName: 'Beta', status: 'UNAVAILABLE', lowest: false },
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

/** Each fetch returns a promise the test releases, so the loading state is observable. */
function deferredFetch() {
  const pending: ((r: Response | Error) => void)[] = [];
  const fetchMock = vi.fn(
    () =>
      new Promise<Response>((resolveFetch, reject) => {
        pending.push((r) => (r instanceof Error ? reject(r) : resolveFetch(r)));
      }),
  );
  vi.stubGlobal('fetch', fetchMock);
  const release = async (r: Response | Error) => {
    await waitFor(() => expect(pending.length).toBeGreaterThan(0));
    pending.shift()!(r);
  };
  return { fetchMock, release };
}

function submit(value: string) {
  const input = screen.getByLabelText(LABEL);
  fireEvent.change(input, { target: { value } });
  fireEvent.submit(input);
  return input;
}

function statusLine() {
  return document.querySelector('.status-line')!;
}

describe('AC 1.8 Lookup states in the page', () => {
  beforeEach(() => window.history.replaceState(null, '', '/'));
  afterEach(() => {
    vi.unstubAllGlobals();
    window.history.replaceState(null, '', '/');
  });

  it('AC 1.8.5: idle shows no status, notice or table; the status line is polite', () => {
    render(<App now={NOW} />);
    expect(statusLine()).toHaveAttribute('aria-live', 'polite');
    expect(statusLine()).toHaveTextContent(/^$/);
    expect(screen.queryByRole('table')).toBeNull();
    expect(document.querySelector('.notice')).toBeNull();
  });

  it('AC 1.8.5: loading shows "Checking retailers…" with a spinner and hides the previous table', async () => {
    const { release } = deferredFetch();
    render(<App now={NOW} />);
    submit('5901234123457');
    expect(statusLine()).toHaveTextContent(LOADING);
    expect(statusLine().querySelector('.spinner')).toHaveAttribute('aria-hidden', 'true');
    await release(json(200, twoRows));
    expect(await screen.findByRole('table')).toBeInTheDocument();
    submit('5901234123457');
    expect(statusLine()).toHaveTextContent(LOADING);
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('AC 1.8.5: results show the summary and the table, no notice', async () => {
    const { release } = deferredFetch();
    render(<App now={NOW} />);
    submit('5901234123457');
    await release(json(200, twoRows));
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(statusLine()).toHaveTextContent(/^Found prices at 1 of 2 retailers\.$/);
    expect(document.querySelector('.notice')).toBeNull();
    expect(screen.getByText('today, 14:05')).toBeInTheDocument();
  });

  it('AC 1.8.5: all failed shows the summary, the all-failed notice and the full table', async () => {
    const { release } = deferredFetch();
    render(<App now={NOW} />);
    submit('5901234123457');
    await release(json(200, allFailed));
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(statusLine()).toHaveTextContent('Found prices at 0 of 2 retailers.');
    expect(screen.getByText(ALL_FAILED)).toHaveClass('notice');
    expect(screen.getAllByRole('row')).toHaveLength(3);
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByText(ALL_FAILED)).toHaveAttribute('role', 'status');
  });

  it('AC 1.8.5: a FOUND row without a price is not counted, so the all-failed notice shows', async () => {
    const { release } = deferredFetch();
    render(<App now={NOW} />);
    submit('5901234123457');
    const unpriced = { ...twoRows.rows[0], priceMinor: undefined, lowest: false };
    await release(json(200, { ean: twoRows.ean, rows: [unpriced, allFailed.rows[0]] }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(statusLine()).toHaveTextContent(/^Found prices at 0 of 2 retailers\.$/);
    expect(screen.getByText(ALL_FAILED)).toHaveClass('notice');
  });

  it('AC 1.8.5: a server EAN error ends loading: field error, empty status line, no table', async () => {
    const { release } = deferredFetch();
    render(<App now={NOW} />);
    submit('5901234123457');
    await release(json(400, { code: 'EAN_CHECK_DIGIT' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Error: the last digit does not match. Check the EAN for a typo.',
    );
    expect(statusLine()).toHaveTextContent(/^$/);
    expect(statusLine().querySelector('.spinner')).toBeNull();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it.each([
    ['a network error', () => new TypeError('Failed to fetch')],
    ['a 500', () => json(500, { title: 'Internal Server Error' })],
    ['a 400 without a known code', () => json(400, { code: 'OTHER' })],
  ])('AC 1.8.5: %s shows the request-failed alert, no table and no status', async (_, make) => {
    const { release } = deferredFetch();
    render(<App now={NOW} />);
    submit('5901234123457');
    await release(make());
    expect(await screen.findByRole('alert')).toHaveTextContent(REQUEST_FAILED);
    expect(screen.getByRole('alert')).toHaveClass('notice');
    expect(screen.queryByRole('table')).toBeNull();
    expect(statusLine()).toHaveTextContent(/^$/);
  });

  it('AC 1.8.5: an invalid submit after results keeps the table and hides the status line', async () => {
    const { release } = deferredFetch();
    render(<App now={NOW} />);
    submit('5901234123457');
    await release(json(200, twoRows));
    expect(await screen.findByRole('table')).toBeInTheDocument();
    submit('123');
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Error: an EAN has 8 or 13 digits. You entered 3.',
    );
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(statusLine()).toHaveTextContent(/^$/);
  });

  it('AC 1.8.5: an invalid submit while loading returns to idle and ignores the late response', async () => {
    const { release } = deferredFetch();
    render(<App now={NOW} />);
    submit('5901234123457');
    submit('123');
    expect(statusLine()).toHaveTextContent(/^$/);
    await release(json(200, twoRows));
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('AC 1.8.5: the spinner animation is off under prefers-reduced-motion', () => {
    const reduced = /@media\s*\(prefers-reduced-motion:\s*reduce\)\s*\{([\s\S]*?)\}\s*\}/.exec(
      baseCss,
    )?.[1];
    expect(reduced).toMatch(/\.spinner\s*\{[^}]*animation:\s*none/);
    expect(baseCss).toMatch(/\.spinner\s*\{[^}]*animation:\s*spinner-turn/);
  });

  it('AC 1.8.6: focus stays in the input when results arrive', async () => {
    const { release } = deferredFetch();
    render(<App now={NOW} />);
    const input = submit('5901234123457');
    await release(json(200, twoRows));
    await screen.findByRole('table');
    expect(input).toHaveFocus();
  });

  it('AC 1.8.6: a focus-visible rule exists', () => {
    expect(baseCss).toMatch(
      /:focus-visible\s*\{[^}]*outline:\s*2px solid var\(--colors-focus-ring\)/,
    );
  });

  it('AC 1.8.6: opening /?ean=<valid> shows loading, then results', async () => {
    window.history.replaceState(null, '', '/?ean=5901234123457');
    const { fetchMock, release } = deferredFetch();
    render(<App now={NOW} />);
    expect(statusLine()).toHaveTextContent(LOADING);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await release(json(200, twoRows));
    expect(await screen.findByRole('table')).toBeInTheDocument();
  });

  it.each([
    ['results', twoRows],
    ['all failed', allFailed],
  ])('AC 1.8.6: axe reports no serious or critical violations in the %s state', async (_, body) => {
    const { release } = deferredFetch();
    render(<App now={NOW} />);
    submit('5901234123457');
    await release(json(200, body));
    await screen.findByRole('table');
    const results = await axe.run(document.body);
    const severe = results.violations.filter(
      (v) => v.impact === 'serious' || v.impact === 'critical',
    );
    expect(severe.map((v) => v.id)).toEqual([]);
  });
});
