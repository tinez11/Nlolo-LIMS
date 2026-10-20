/**
 * Validation patterns mirroring exact backend regexes/formats, kept in one place
 * so a client-side rule can't silently drift from the server rule it exists to
 * anticipate. Every form on this platform that validates a UUID, a policy number,
 * or a plain calendar date should use these rather than a local copy.
 */

/** Matches `party/infrastructure` and `policyloan`/`claims` UUID path variables. */
export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** `openapi-common.yaml#/components/schemas/PolicyNumberRef`. */
export const POLICY_NUMBER_PATTERN = /^[A-Z0-9-]{6,20}$/;

/** What a native `<input type="date">` always produces when filled. */
export const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** An ISO 4217 currency code, e.g. `policy.infrastructure.MoneyDto.currencyCode`. */
export const CURRENCY_PATTERN = /^[A-Z]{3}$/;

/** The one form a phone number is stored in -- Tanzanian E.164 with the plus. */
export const PHONE_PATTERN = /^\+255\d{9}$/;

/** What a person sees when a number is not a Tanzanian one. */
export const PHONE_MESSAGE = 'Enter a Tanzanian number, e.g. 0712 345 678 or +255712345678';

/**
 * A Tanzanian number written however a person writes it -- `0712 345 678`, `255712345678`, `+255 712-345-678`,
 * `00255712345678` -- as the one stored form `+255712345678`; null when it is not one (2026-10-20). The same rules as
 * the server's `party.api.PhoneNumbers`, which converts again on save, so the browser only ever saves time.
 */
export function normalisePhone(written: string): string | null {
  const digits = written.trim().replace(/[\s\-.()]/g, '');
  const national = digits.startsWith('+255')
    ? digits.slice(4)
    : digits.startsWith('00255')
      ? digits.slice(5)
      : digits.startsWith('255')
        ? digits.slice(3)
        : digits.startsWith('0')
          ? digits.slice(1)
          : null;
  return national !== null && /^[1-9]\d{8}$/.test(national) ? `+255${national}` : null;
}
