import { availabilityText, formatFetched, formatPrice, statusText } from './format';
import { ResultRowStatusEnum, type LookupResponse, type ResultRow } from './generated';

export interface ResultsTableProps {
  response: LookupResponse;
  now: Date;
}

function NotApplicable() {
  return (
    <>
      <span aria-hidden="true">—</span>
      <span className="visually-hidden">not applicable</span>
    </>
  );
}

function Row({ row, now }: { row: ResultRow; now: Date }) {
  const found = row.status === ResultRowStatusEnum.Found;
  // Green and badge always together, and only on a priced FOUND row (EXPERIENCE.md, AC-4.1).
  const lowest = found && row.lowest && row.priceMinor !== undefined;
  return (
    <tr className={lowest ? 'row-lowest' : undefined}>
      <td>{row.retailerName}</td>
      <td className="price-cell">
        {found ? (
          <>
            {row.priceMinor === undefined ? 'unavailable' : formatPrice(row.priceMinor)}
            {lowest ? <span className="badge-lowest">Lowest price</span> : null}
          </>
        ) : (
          <span className="row-status-text">{statusText(row.status)}</span>
        )}
      </td>
      <td>{found ? availabilityText(row.availability) : <NotApplicable />}</td>
      <td>
        {found && row.fetchedAt ? (
          <time dateTime={row.fetchedAt.toISOString()}>{formatFetched(row.fetchedAt, now)}</time>
        ) : (
          <NotApplicable />
        )}
      </td>
      <td>
        {found && row.url ? (
          <a href={row.url} target="_blank" rel="noopener noreferrer">
            View at {row.retailerName} <span className="visually-hidden">(opens in a new tab)</span>
          </a>
        ) : (
          <NotApplicable />
        )}
      </td>
    </tr>
  );
}

/** Results table (EXPERIENCE.md Component Patterns). Rows keep the server's order (AD-4). */
export function ResultsTable({ response, now }: ResultsTableProps) {
  return (
    <div className="results-scroll" role="region" aria-label="Price results" tabIndex={0}>
      <table className="results-table">
        <caption>Prices for EAN {response.ean}</caption>
        <thead>
          <tr>
            <th scope="col">Retailer</th>
            <th scope="col">Price</th>
            <th scope="col">Availability</th>
            <th scope="col">Fetched</th>
            <th scope="col">Link</th>
          </tr>
        </thead>
        <tbody>
          {response.rows.map((row) => (
            <Row key={row.retailerId} row={row} now={now} />
          ))}
        </tbody>
      </table>
    </div>
  );
}
