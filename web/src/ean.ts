import { EanErrorCode } from './generated';

export type EanValidation = { valid: true; ean: string } | { valid: false; code: EanErrorCode };

const DIGITS = /^[0-9]+$/;

function checkDigitMatches(digits: string): boolean {
  // GS1: weights 3,1,3,... from the digit left of the check digit, moving left.
  let sum = 0;
  for (let i = digits.length - 2, weight = 3; i >= 0; i--, weight = 4 - weight) {
    sum += Number(digits[i]) * weight;
  }
  return (10 - (sum % 10)) % 10 === Number(digits[digits.length - 1]);
}

/** One EAN rule for the web, matching api/ean-cases.json (AD-13). */
export function validateEan(input: string): EanValidation {
  const ean = input.trim();
  if (ean === '') return { valid: false, code: EanErrorCode.EanEmpty };
  if (!DIGITS.test(ean)) return { valid: false, code: EanErrorCode.EanNonDigit };
  if (ean.length !== 8 && ean.length !== 13) return { valid: false, code: EanErrorCode.EanLength };
  if (!checkDigitMatches(ean)) return { valid: false, code: EanErrorCode.EanCheckDigit };
  return { valid: true, ean };
}

/** Voice and Tone copy (EXPERIENCE.md) for an EAN error code; `{n}` is the trimmed input length. */
export function eanErrorMessage(code: EanErrorCode, input: string): string {
  switch (code) {
    case EanErrorCode.EanEmpty:
      return 'Error: enter an EAN.';
    case EanErrorCode.EanNonDigit:
      return 'Error: an EAN contains only digits.';
    case EanErrorCode.EanLength:
      return `Error: an EAN has 8 or 13 digits. You entered ${input.trim().length}.`;
    case EanErrorCode.EanCheckDigit:
      return 'Error: the last digit does not match. Check the EAN for a typo.';
  }
}

export function isEanErrorCode(value: unknown): value is EanErrorCode {
  return (Object.values(EanErrorCode) as unknown[]).includes(value);
}
