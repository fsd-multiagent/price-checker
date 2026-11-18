// QA coverage for story 1-7 (Eufrosina (QA)): gaps around the existing App/EanForm/ean tests (T-1, T-7).
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { eanErrorMessage } from './ean';
import { EanErrorCode } from './generated';

const LABEL = 'EAN (8 or 13 digits)';
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
  return (JSON.parse(raw) as { cases: EanCase[] }).cases;
}

function jsonResponse(status: number, body: unknown, contentType = 'application/json'): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}

function stubFetch(response: () => Response | Promise<Response>) {
  const fetchMock = vi.fn(async () => response());
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function requestedUrl(fetchMock: ReturnType<typeof stubFetch>, call = 0): string {
  const args = fetchMock.mock.calls[call] as unknown[] | undefined;
  return String(args?.[0]);
}

function submit(value: string) {
  const input = screen.getByLabelText(LABEL);
  fireEvent.change(input, { target: { value } });
  fireEvent.submit(input);
  return input;
}

/** Lets pending promise callbacks (fetch rejection, body parsing) run; no timers or sleeps (T-5). */
async function settle() {
  for (let i = 0; i < 5; i++) await Promise.resolve();
  await new Promise((r) => setTimeout(r, 0));
}

beforeEach(() => {
  window.history.replaceState(null, '', '/');
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

describe('AC 1.7.1 shared EAN cases cover T-7', () => {
  it('AC 1.7.1: api/ean-cases.json has valid EAN-8 and EAN-13, wrong length, non-digit and bad check digit cases', () => {
    const cases = eanCases();
    const validLengths = new Set(cases.filter((c) => c.valid).map((c) => c.ean?.length));
    expect(validLengths).toEqual(new Set([8, 13]));
    const codes = new Set(cases.filter((c) => !c.valid).map((c) => c.code));
    for (const code of Object.values(EanErrorCode)) expect(codes).toContain(code);
  });
});

describe('AC 1.7.3 every invalid shared case is stopped in the page', () => {
  it.each(
    eanCases()
      .filter((c) => !c.valid)
      .map((c) => [c.name, c] as const),
  )('AC 1.7.3: %s shows its copy, makes no request and keeps the URL', (_name, c) => {
    const fetchMock = stubFetch(() => jsonResponse(200, { ean: '', rows: [] }));
    render(<App />);
    const input = submit(c.input);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent(
      eanErrorMessage(c.code as EanErrorCode, c.input),
    );
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveFocus();
    expect(window.location.search).toBe('');
  });

  it('AC 1.7.3: a valid submit after an error clears the error', async () => {
    const fetchMock = stubFetch(() => jsonResponse(200, { ean: '96385074', rows: [] }));
    render(<App />);
    submit('96385075');
    expect(screen.getByRole('alert')).toBeInTheDocument();
    const input = screen.getByLabelText(LABEL);
    fireEvent.change(input, { target: { value: '96385074' } });
    fireEvent.submit(input);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(input).not.toHaveAttribute('aria-invalid');
  });

  it('AC 1.7.3: re-submitting the same invalid value keeps the error shown', () => {
    stubFetch(() => jsonResponse(200, { ean: '', rows: [] }));
    render(<App />);
    const input = submit('abc');
    fireEvent.submit(input);
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(screen.getByRole('alert')).toHaveTextContent('Error: an EAN contains only digits.');
  });
});

describe('AC 1.7.4 server 400 problem+json', () => {
  it.each(Object.values(EanErrorCode).map((code) => [code] as const))(
    'AC 1.7.4: server code %s shows the client copy for that code',
    async (code) => {
      stubFetch(() =>
        jsonResponse(
          400,
          { type: 'about:blank', title: 'Bad Request', status: 400, code },
          'application/problem+json',
        ),
      );
      render(<App />);
      const input = submit('96385074');
      expect(await screen.findByRole('alert')).toHaveTextContent(eanErrorMessage(code, '96385074'));
      expect(input).toHaveAttribute('aria-invalid', 'true');
      expect(input.getAttribute('aria-describedby')?.split(' ')).toContain(
        screen.getByRole('alert').id,
      );
    },
  );

  it('AC 1.7.4: a 500 with an EAN code is not shown as an EAN error', async () => {
    const fetchMock = stubFetch(() =>
      jsonResponse(500, { code: 'EAN_CHECK_DIGIT' }, 'application/problem+json'),
    );
    render(<App />);
    submit('96385074');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await settle();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('AC 1.7.4: a 400 whose body is not JSON shows no EAN error', async () => {
    const fetchMock = stubFetch(
      () => new Response('Bad Request', { status: 400, headers: { 'Content-Type': 'text/plain' } }),
    );
    render(<App />);
    submit('96385074');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await settle();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('AC 1.7.4: a 400 for a Lookup started from /?ean= shows the client copy', async () => {
    window.history.replaceState(null, '', '/?ean=5901234123457');
    stubFetch(() => jsonResponse(400, { code: 'EAN_CHECK_DIGIT' }, 'application/problem+json'));
    render(<App />);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Error: the last digit does not match. Check the EAN for a typo.',
    );
  });
});

describe('AC 1.7.5 URL state', () => {
  it('AC 1.7.5: a second valid submit replaces ?ean= without adding history entries', async () => {
    const fetchMock = stubFetch(() => jsonResponse(200, { ean: '', rows: [] }));
    const historyLength = window.history.length;
    render(<App />);
    submit('96385074');
    submit('5901234123457');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(window.location.search).toBe('?ean=5901234123457');
    expect(window.history.length).toBe(historyLength);
    expect(requestedUrl(fetchMock, 1)).toBe('/api/lookup?ean=5901234123457');
  });

  it('AC 1.7.5: an invalid submit after a valid one leaves the URL at the last valid EAN', async () => {
    const fetchMock = stubFetch(() => jsonResponse(200, { ean: '96385074', rows: [] }));
    render(<App />);
    submit('96385074');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    submit('123');
    expect(window.location.search).toBe('?ean=96385074');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('AC 1.7.5: opening /?ean= with a padded valid EAN looks up the trimmed digits', async () => {
    window.history.replaceState(null, '', '/?ean=%2096385074%20');
    const fetchMock = stubFetch(() => jsonResponse(200, { ean: '96385074', rows: [] }));
    render(<App />);
    expect(screen.getByLabelText(LABEL)).toHaveValue(' 96385074 ');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(requestedUrl(fetchMock)).toBe('/api/lookup?ean=96385074');
  });

  it('AC 1.7.5: opening / without ?ean= leaves the input empty and makes no request', async () => {
    const fetchMock = stubFetch(() => jsonResponse(200, { ean: '', rows: [] }));
    render(<App />);
    expect(screen.getByLabelText(LABEL)).toHaveValue('');
    await settle();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('AC 1.7.5: opening /?ean=<valid> runs exactly one Lookup', async () => {
    window.history.replaceState(null, '', '/?ean=4006381333931');
    const fetchMock = stubFetch(() => jsonResponse(200, { ean: '4006381333931', rows: [] }));
    render(<App />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(window.location.search).toBe('?ean=4006381333931');
  });
});
