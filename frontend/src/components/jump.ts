/**
 * An exact reference the palette can open without a search endpoint.
 *
 * Only references whose shape alone says what they are: a policy number, and since receipts step 1 (2026-11-05) a
 * receipt number -- the one a customer reads off their SMS. Claims, parties and underwriting cases are bare UUIDs -- a
 * pasted UUID could be any of the three, and guessing would land a staff member on the wrong record's 404.
 */
export function resolveJump(query: string): { to: string; label: string } | null {
  const reference = query.trim().toUpperCase();
  if (/^POL-[A-Z0-9]+$/.test(reference)) return { to: `policies/${reference}`, label: `Open policy ${reference}` };
  if (/^RC-\d{4}-\d{6}$/.test(reference)) return { to: `receipt-numbers/${reference}`, label: `Open receipt ${reference}` };
  return null;
}
