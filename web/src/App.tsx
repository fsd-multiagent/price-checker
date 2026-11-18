import { useCallback, useEffect, useRef, useState } from 'react';
import { EanForm } from './EanForm';
import { eanErrorMessage, isEanErrorCode, validateEan } from './ean';
import { Configuration, LookupApi, ResponseError, ResultRowStatusEnum } from './generated';
import type { LookupResponse } from './generated';
import { Notice } from './Notice';
import { ResultsTable } from './ResultsTable';
import { StatusLine } from './StatusLine';

const lookupApi = new LookupApi(new Configuration({ basePath: '' }));

function initialEan(): string {
  return new URLSearchParams(window.location.search).get('ean') ?? '';
}

async function problemCode(error: unknown): Promise<unknown> {
  if (!(error instanceof ResponseError) || error.response.status !== 400) return undefined;
  try {
    const body = (await error.response.json()) as { code?: unknown };
    return body.code;
  } catch {
    return undefined;
  }
}

/** Lookup states (EXPERIENCE.md State Patterns); `stale` is a previous table after an invalid submit. */
type Lookup =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'results'; response: LookupResponse; now: Date }
  | { kind: 'stale'; response: LookupResponse; now: Date }
  | { kind: 'failed' };

const REQUEST_FAILED = 'Could not reach Price Checker. Check your connection and try again.';
const ALL_FAILED =
  'No prices found for this EAN. Retailers that could not be reached are marked Unavailable; try again in a minute.';

function foundWithPrice(response: LookupResponse): number {
  return response.rows.filter(
    (row) => row.status === ResultRowStatusEnum.Found && row.priceMinor !== undefined,
  ).length;
}

export interface AppProps {
  /** Clock for "today" in fetch times; injected in tests (T-5). */
  now?: () => Date;
}

const systemNow = () => new Date();

export function App({ now = systemNow }: AppProps = {}) {
  const [value, setValue] = useState(initialEan);
  const [error, setError] = useState<string | null>(null);
  // A valid /?ean= on load starts a Lookup from the mount effect, so the page starts loading.
  const [lookup, setLookup] = useState<Lookup>(() =>
    validateEan(initialEan()).valid ? { kind: 'loading' } : { kind: 'idle' },
  );
  const latestRequest = useRef(0);
  const nowRef = useRef(now);
  useEffect(() => {
    nowRef.current = now;
  }, [now]);
  const editedSinceSubmit = useRef(false);

  const startLookup = useCallback((ean: string) => {
    const requestId = ++latestRequest.current;
    lookupApi.lookup({ ean }).then(
      (response) => {
        if (requestId !== latestRequest.current) return;
        setLookup({ kind: 'results', response, now: nowRef.current() });
      },
      async (err: unknown) => {
        const code = await problemCode(err);
        if (requestId !== latestRequest.current) return;
        if (!isEanErrorCode(code)) {
          setLookup({ kind: 'failed' });
          return;
        }
        setLookup({ kind: 'idle' });
        if (!editedSinceSubmit.current) setError(eanErrorMessage(code, ean));
      },
    );
  }, []);

  useEffect(() => {
    const result = validateEan(initialEan());
    if (result.valid) startLookup(result.ean);
  }, [startLookup]);

  function handleChange(next: string) {
    editedSinceSubmit.current = true;
    setValue(next);
    setError(null);
  }

  function handleSubmit() {
    editedSinceSubmit.current = false;
    const result = validateEan(value);
    if (!result.valid) {
      latestRequest.current++;
      setError(eanErrorMessage(result.code, value));
      setLookup((current) =>
        current.kind === 'results' || current.kind === 'stale'
          ? { ...current, kind: 'stale' }
          : { kind: 'idle' },
      );
      return;
    }
    setError(null);
    window.history.replaceState(null, '', `/?ean=${result.ean}`);
    setLookup({ kind: 'loading' });
    startLookup(result.ean);
  }

  return (
    <div className="page">
      <header>
        <h1 className="page-title">Price Checker</h1>
        <p className="page-intro">
          Enter a product&apos;s EAN to compare prices at approved Romanian retailers.
        </p>
      </header>
      <main>
        <EanForm value={value} error={error} onChange={handleChange} onSubmit={handleSubmit} />
        <StatusLine
          {...(lookup.kind === 'loading'
            ? { kind: 'loading' }
            : lookup.kind === 'results'
              ? {
                  kind: 'summary',
                  found: foundWithPrice(lookup.response),
                  total: lookup.response.rows.length,
                }
              : { kind: 'hidden' })}
        />
        {lookup.kind === 'failed' ? <Notice role="alert">{REQUEST_FAILED}</Notice> : null}
        {lookup.kind === 'results' && foundWithPrice(lookup.response) === 0 ? (
          <Notice role="status">{ALL_FAILED}</Notice>
        ) : null}
        {lookup.kind === 'results' || lookup.kind === 'stale' ? (
          <ResultsTable response={lookup.response} now={lookup.now} />
        ) : null}
      </main>
      <footer className="page-footer">
        <p>
          Prices include VAT. Times are Romanian local time. Only approved retailers are checked.
        </p>
      </footer>
    </div>
  );
}
