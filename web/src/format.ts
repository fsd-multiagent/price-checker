import { ResultRowAvailabilityEnum, ResultRowStatusEnum } from './generated';

const priceFormat = new Intl.NumberFormat('ro-RO', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
  useGrouping: 'always',
});

/** Price in bani to Romanian format, e.g. 129999 -> "1.299,99 lei" (EXPERIENCE.md Foundation). */
export function formatPrice(minor: number): string {
  return `${priceFormat.format(minor / 100)} lei`;
}

const bucharestParts = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Europe/Bucharest',
  year: 'numeric',
  month: 'numeric',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function inBucharest(date: Date) {
  const parts: Record<string, string> = {};
  for (const part of bucharestParts.formatToParts(date)) parts[part.type] = part.value;
  return {
    year: parts.year ?? '',
    month: Number(parts.month),
    day: Number(parts.day),
    time: `${parts.hour ?? ''}:${parts.minute ?? ''}`,
  };
}

/**
 * Fetch time in Europe/Bucharest: "today, 14:05" when it is the same Bucharest day as `now`,
 * otherwise "17 Nov 2026, 14:05" (EXPERIENCE.md Voice and Tone). `now` is injected (T-5).
 */
export function formatFetched(date: Date, now: Date): string {
  const at = inBucharest(date);
  const today = inBucharest(now);
  if (at.year === today.year && at.month === today.month && at.day === today.day) {
    return `today, ${at.time}`;
  }
  return `${at.day} ${MONTHS[at.month - 1] ?? ''} ${at.year}, ${at.time}`;
}

const AVAILABILITY_TEXT: Record<ResultRowAvailabilityEnum, string> = {
  [ResultRowAvailabilityEnum.InStock]: 'In stock',
  [ResultRowAvailabilityEnum.OutOfStock]: 'Out of stock',
  [ResultRowAvailabilityEnum.Unknown]: 'Unknown',
};

export function availabilityText(availability: ResultRowAvailabilityEnum | undefined): string {
  return AVAILABILITY_TEXT[availability ?? ResultRowAvailabilityEnum.Unknown];
}

const STATUS_TEXT: Record<ResultRowStatusEnum, string> = {
  [ResultRowStatusEnum.Found]: 'Found',
  [ResultRowStatusEnum.NotFound]: 'Not found',
  [ResultRowStatusEnum.Unavailable]: 'Unavailable',
  [ResultRowStatusEnum.Disabled]: 'Disabled',
};

export function statusText(status: ResultRowStatusEnum): string {
  return STATUS_TEXT[status];
}
