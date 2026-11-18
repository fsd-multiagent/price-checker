export type StatusLineProps =
  { kind: 'hidden' } | { kind: 'loading' } | { kind: 'summary'; found: number; total: number };

/** Status line (EXPERIENCE.md State Patterns): always mounted so `aria-live` announces changes. */
export function StatusLine(props: StatusLineProps) {
  return (
    <p className="status-line" aria-live="polite">
      {props.kind === 'loading' ? (
        <>
          <span className="spinner" aria-hidden="true" />
          Checking retailers…
        </>
      ) : null}
      {props.kind === 'summary'
        ? `Found prices at ${props.found} of ${props.total} retailers.`
        : null}
    </p>
  );
}
