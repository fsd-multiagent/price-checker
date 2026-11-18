import { useCallback, useEffect, useRef, useState } from 'react';
import { EanForm } from './EanForm';
import { eanErrorMessage, isEanErrorCode, validateEan } from './ean';
import { Configuration, LookupApi, ResponseError } from './generated';

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

export function App() {
  const [value, setValue] = useState(initialEan);
  const [error, setError] = useState<string | null>(null);
  const latestRequest = useRef(0);
  const editedSinceSubmit = useRef(false);

  const startLookup = useCallback((ean: string) => {
    const requestId = ++latestRequest.current;
    // The response is rendered by story 1.8; here only a 400 problem+json code is shown.
    lookupApi.lookup({ ean }).catch(async (err: unknown) => {
      const code = await problemCode(err);
      if (requestId !== latestRequest.current || editedSinceSubmit.current) return;
      if (isEanErrorCode(code)) setError(eanErrorMessage(code, ean));
    });
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
      return;
    }
    setError(null);
    window.history.replaceState(null, '', `/?ean=${result.ean}`);
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
      </main>
      <footer className="page-footer">
        <p>
          Prices include VAT. Times are Romanian local time. Only approved retailers are checked.
        </p>
      </footer>
    </div>
  );
}
