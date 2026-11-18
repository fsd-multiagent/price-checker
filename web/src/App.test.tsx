import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import axe from 'axe-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';

const webRoot = resolve(import.meta.dirname, '..');

describe('AC 1.2.3 page shell', () => {
  it('AC 1.2.3: shows the heading, explanation line and footer note', () => {
    render(<App />);
    expect(screen.getByRole('heading', { level: 1, name: 'Price Checker' })).toBeInTheDocument();
    expect(
      screen.getByText("Enter a product's EAN to compare prices at approved Romanian retailers."),
    ).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toHaveTextContent(
      'Prices include VAT. Times are Romanian local time. Only approved retailers are checked.',
    );
  });

  it('AC 1.2.3: index.html declares lang="en"', () => {
    const html = readFileSync(resolve(webRoot, 'index.html'), 'utf8');
    expect(html).toMatch(/<html[^>]*\slang="en"/);
  });

  it('AC 1.2.3: the column is 880px wide and centred', () => {
    const tokens = readFileSync(resolve(import.meta.dirname, 'styles/tokens.css'), 'utf8');
    const base = readFileSync(resolve(import.meta.dirname, 'styles/base.css'), 'utf8');
    expect(tokens).toMatch(/--spacing-content-max-width:\s*880px;/);
    const page = /\.page\s*\{([^}]*)\}/.exec(base)?.[1] ?? '';
    expect(page).toMatch(/max-width:\s*var\(--spacing-content-max-width\);/);
    expect(page).toMatch(/margin-left:\s*auto;/);
    expect(page).toMatch(/margin-right:\s*auto;/);
    expect(page).toMatch(/padding:\s*var\(--spacing-xl\)/);
  });

  it('AC 1.2.3: the header is the banner landmark holding the heading and explanation', () => {
    render(<App />);
    const banner = screen.getByRole('banner');
    expect(banner).toContainElement(screen.getByRole('heading', { level: 1 }));
    expect(banner).toHaveTextContent("Enter a product's EAN");
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('AC 1.2.3: header, main and footer render in order inside the column', () => {
    const { container } = render(<App />);
    const page = container.querySelector('.page');
    expect(page).not.toBeNull();
    const landmarks = [...page!.children].map((el) => el.tagName.toLowerCase());
    expect(landmarks).toEqual(['header', 'main', 'footer']);
  });

  it('AC 1.2.3: index.html has the #root mount point and the page title', () => {
    const html = readFileSync(resolve(webRoot, 'index.html'), 'utf8');
    expect(html).toMatch(/<div id="root"><\/div>/);
    expect(html).toMatch(/<title>Price Checker<\/title>/);
    expect(html).toMatch(/<meta name="viewport"[^>]*width=device-width/);
  });

  it('AC 1.2.3: axe reports no serious or critical violations', async () => {
    render(<App />);
    const results = await axe.run(document.body);
    const severe = results.violations.filter(
      (v) => v.impact === 'serious' || v.impact === 'critical',
    );
    expect(severe.map((v) => v.id)).toEqual([]);
  });
});

const LABEL = 'EAN (8 or 13 digits)';

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

describe('AC 1.7 EAN form in the page', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    window.history.replaceState(null, '', '/');
  });

  it('AC 1.7.2: the form is inside main with an autofocused input and the button', () => {
    render(<App />);
    const main = screen.getByRole('main');
    const input = screen.getByLabelText(LABEL);
    expect(main).toContainElement(input);
    expect(main).toContainElement(screen.getByRole('button', { name: 'Check prices' }));
    expect(input).toHaveFocus();
  });

  it.each([
    ['', 'Error: enter an EAN.'],
    ['   ', 'Error: enter an EAN.'],
    ['4006 381333931', 'Error: an EAN contains only digits.'],
    ['1234567', 'Error: an EAN has 8 or 13 digits. You entered 7.'],
    ['4006381333932', 'Error: the last digit does not match. Check the EAN for a typo.'],
  ])('AC 1.7.3: %j shows its error without a request', (value, copy) => {
    const fetchMock = stubFetch(() => jsonResponse(200, { ean: '', rows: [] }));
    render(<App />);
    const input = submit(value);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent(copy);
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input.getAttribute('aria-describedby')?.split(' ')).toContain(
      screen.getByRole('alert').id,
    );
    expect(input).toHaveFocus();
    expect(window.location.search).toBe('');
  });

  it('AC 1.7.3: clicking the button with invalid input shows the error too', () => {
    const fetchMock = stubFetch(() => jsonResponse(200, { ean: '', rows: [] }));
    render(<App />);
    fireEvent.change(screen.getByLabelText(LABEL), { target: { value: 'abc' } });
    fireEvent.click(screen.getByRole('button', { name: 'Check prices' }));
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('Error: an EAN contains only digits.');
  });

  it('AC 1.7.3: typing clears the error', () => {
    stubFetch(() => jsonResponse(200, { ean: '', rows: [] }));
    render(<App />);
    const input = submit('123');
    expect(screen.getByRole('alert')).toBeInTheDocument();
    fireEvent.change(input, { target: { value: '1234' } });
    expect(screen.queryByRole('alert')).toBeNull();
    expect(input).not.toHaveAttribute('aria-invalid');
  });

  it('AC 1.7.3: axe reports no serious or critical violations with an error shown', async () => {
    stubFetch(() => jsonResponse(200, { ean: '', rows: [] }));
    render(<App />);
    submit('123');
    const results = await axe.run(document.body);
    const severe = results.violations.filter(
      (v) => v.impact === 'serious' || v.impact === 'critical',
    );
    expect(severe.map((v) => v.id)).toEqual([]);
  });

  it('AC 1.7.4: a 400 problem+json code shows the client copy for that code', async () => {
    stubFetch(() =>
      jsonResponse(
        400,
        { type: 'about:blank', title: 'Bad Request', status: 400, code: 'EAN_CHECK_DIGIT' },
        'application/problem+json',
      ),
    );
    render(<App />);
    const input = submit('96385074');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Error: the last digit does not match. Check the EAN for a typo.',
    );
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveFocus();
  });

  it('AC 1.7.4: a 400 without a known code shows no EAN error (AC 1.8.5: request-failed notice)', async () => {
    const fetchMock = stubFetch(() =>
      jsonResponse(400, { code: 'OTHER' }, 'application/problem+json'),
    );
    render(<App />);
    const input = submit('96385074');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not reach Price Checker. Check your connection and try again.',
    );
    expect(input).not.toHaveAttribute('aria-invalid');
    expect(screen.queryByText(/^Error:/)).toBeNull();
  });

  it('AC 1.7.4: a 400 for a superseded Lookup is ignored', async () => {
    let release: (r: Response) => void = () => {};
    const first = new Promise<Response>((r) => {
      release = r;
    });
    const responses = [
      () => first,
      () => Promise.resolve(jsonResponse(200, { ean: '5901234123457', rows: [] })),
    ];
    const fetchMock = vi.fn(async () => responses.shift()!());
    vi.stubGlobal('fetch', fetchMock);
    render(<App />);
    submit('96385074');
    submit('5901234123457');
    release(jsonResponse(400, { code: 'EAN_CHECK_DIGIT' }, 'application/problem+json'));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it.each([
    ['an invalid submit', 'Error: an EAN has 8 or 13 digits. You entered 3.'],
    ['typing', null],
  ])('AC 1.7.4: a late 400 after %s does not replace the current state', async (kind, copy) => {
    let release: (r: Response) => void = () => {};
    const first = new Promise<Response>((r) => {
      release = r;
    });
    const fetchMock = vi.fn(async () => first);
    vi.stubGlobal('fetch', fetchMock);
    const json = vi.spyOn(Response.prototype, 'json');
    render(<App />);
    const input = submit('96385074');
    if (kind === 'typing') fireEvent.change(input, { target: { value: '9638507' } });
    else submit('123');
    release(jsonResponse(400, { code: 'EAN_CHECK_DIGIT' }, 'application/problem+json'));
    await waitFor(() => expect(json).toHaveBeenCalled());
    await json.mock.results[0]?.value;
    await new Promise((r) => setTimeout(r, 0));
    json.mockRestore();
    if (copy) expect(screen.getByRole('alert')).toHaveTextContent(copy);
    else expect(screen.queryByRole('alert')).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('AC 1.7.5: a valid padded submit calls the same-origin Lookup and sets /?ean=', async () => {
    const fetchMock = stubFetch(() => jsonResponse(200, { ean: '96385074', rows: [] }));
    render(<App />);
    const input = submit(' 96385074 ');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(requestedUrl(fetchMock)).toBe('/api/lookup?ean=96385074');
    expect(window.location.pathname).toBe('/');
    expect(window.location.search).toBe('?ean=96385074');
    expect(screen.queryByRole('alert')).toBeNull();
    expect(input).toHaveFocus();
  });

  it('AC 1.7.5: opening /?ean=<valid> fills the input and runs the Lookup', async () => {
    window.history.replaceState(null, '', '/?ean=5901234123457');
    const fetchMock = stubFetch(() => jsonResponse(200, { ean: '5901234123457', rows: [] }));
    render(<App />);
    expect(screen.getByLabelText(LABEL)).toHaveValue('5901234123457');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(requestedUrl(fetchMock)).toBe('/api/lookup?ean=5901234123457');
  });

  it('AC 1.7.5: opening /?ean=<invalid> fills the input without a Lookup or error', async () => {
    window.history.replaceState(null, '', '/?ean=12345');
    const fetchMock = stubFetch(() => jsonResponse(200, { ean: '', rows: [] }));
    render(<App />);
    expect(screen.getByLabelText(LABEL)).toHaveValue('12345');
    await new Promise((r) => setTimeout(r, 0));
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
