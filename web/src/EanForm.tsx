import { useEffect, useRef, type FormEvent } from 'react';

export interface EanFormProps {
  value: string;
  error: string | null;
  onChange: (value: string) => void;
  onSubmit: () => void;
}

const INPUT_ID = 'ean-input';
const HINT_ID = 'ean-hint';
const ERROR_ID = 'ean-error';

/** EAN form (EXPERIENCE.md Component Patterns, UX-DR3/DR4): validation is the caller's, on submit. */
export function EanForm({ value, error, onChange, onSubmit }: EanFormProps) {
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (error) inputRef.current?.focus();
  }, [error]);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onSubmit();
    inputRef.current?.focus();
  }

  const describedBy = error ? `${HINT_ID} ${ERROR_ID}` : HINT_ID;

  return (
    <form className="ean-form" onSubmit={handleSubmit} noValidate>
      <label className="ean-label" htmlFor={INPUT_ID}>
        EAN (8 or 13 digits)
      </label>
      <div className="ean-row">
        <input
          ref={inputRef}
          id={INPUT_ID}
          className="ean-input"
          type="text"
          name="ean"
          inputMode="numeric"
          autoComplete="off"
          autoFocus
          value={value}
          aria-invalid={error ? 'true' : undefined}
          aria-describedby={describedBy}
          onChange={(event) => onChange(event.target.value)}
        />
        <button className="ean-button" type="submit">
          Check prices
        </button>
      </div>
      {error ? (
        <p className="ean-error" id={ERROR_ID} role="alert">
          {error}
        </p>
      ) : null}
      <p className="ean-hint" id={HINT_ID}>
        Find it under the barcode on the box.
      </p>
    </form>
  );
}
