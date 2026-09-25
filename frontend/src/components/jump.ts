/**
 * An exact reference the palette can open without a search endpoint.
 *
 * Only policy numbers: they are the one identifier on the platform whose shape alone says
 * what it is. Claims, parties and underwriting cases are bare UUIDs -- a pasted UUID could
 * be any of the three, and guessing would land a staff member on the wrong record's 404.
 */
export function resolveJump(query: string): string | null {
  const reference = query.trim().toUpperCase();
  return /^POL-[A-Z0-9]+$/.test(reference) ? `policies/${reference}` : null;
}
