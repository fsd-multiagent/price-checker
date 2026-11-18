import { describe, expect, it } from 'vitest';
import { availabilityText, formatFetched, formatPrice, statusText } from './format';

describe('AC 1.8.2 Romanian price and Bucharest time formats', () => {
  it.each([
    [129999, '1.299,99 lei'],
    [100, '1,00 lei'],
    [5, '0,05 lei'],
    [123456789, '1.234.567,89 lei'],
  ])('AC 1.8.2: formatPrice(%d) is %j', (minor, text) => {
    expect(formatPrice(minor).replace(/\s/g, ' ')).toBe(text);
  });

  it('AC 1.8.2: a fetch time on the same Bucharest day reads "today, HH:mm"', () => {
    const now = new Date('2026-11-17T20:00:00Z');
    expect(formatFetched(new Date('2026-11-17T12:05:00Z'), now)).toBe('today, 14:05');
  });

  it('AC 1.8.2: an earlier day reads "D Mon YYYY, HH:mm" in Bucharest time', () => {
    const now = new Date('2026-11-18T09:00:00Z');
    expect(formatFetched(new Date('2026-11-17T12:05:00Z'), now)).toBe('17 Nov 2026, 14:05');
  });

  it('AC 1.8.2: the day boundary is Bucharest midnight, not UTC midnight', () => {
    // 22:30Z on 17 Nov is 00:30 on 18 Nov in Bucharest (UTC+2 in winter).
    const fetched = new Date('2026-11-17T22:30:00Z');
    expect(formatFetched(fetched, new Date('2026-11-18T08:00:00Z'))).toBe('today, 00:30');
    expect(formatFetched(fetched, new Date('2026-11-17T21:00:00Z'))).toBe('18 Nov 2026, 00:30');
  });

  it('AC 1.8.2: summer time is UTC+3', () => {
    const now = new Date('2026-12-01T00:00:00Z');
    expect(formatFetched(new Date('2026-09-05T06:07:00Z'), now)).toBe('5 Sep 2026, 09:07');
  });

  it('AC 1.8.2: availability and status copy match EXPERIENCE.md', () => {
    expect(availabilityText('IN_STOCK')).toBe('In stock');
    expect(availabilityText('OUT_OF_STOCK')).toBe('Out of stock');
    expect(availabilityText('UNKNOWN')).toBe('Unknown');
    expect(availabilityText(undefined)).toBe('Unknown');
    expect(statusText('NOT_FOUND')).toBe('Not found');
    expect(statusText('UNAVAILABLE')).toBe('Unavailable');
    expect(statusText('DISABLED')).toBe('Disabled');
  });
});
