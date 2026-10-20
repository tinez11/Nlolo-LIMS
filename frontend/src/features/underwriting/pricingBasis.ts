/**
 * How a product is priced -- which decides what the quotation panel can even ask for.
 *
 * <p>The premium-quote endpoint prices a sum assured off a base rate table, and that is ALL it can price. Nine of
 * the platform's fourteen portfolios are not priced that way at all, so every one of them used to answer the panel
 * with a red 422 ("carries no base rate table, so it cannot be priced") on a product working exactly as designed.
 * A quotation panel that only ever works for term and endowment is not a quotation panel.
 *
 * <p>Its own module (2026-10-20) so the agents' calculator can ask the same question the panel does.
 */
export type PricingBasis = 'PREMIUM' | 'SAVINGS' | 'FUNERAL' | 'OWN_SECTION' | 'PER_SCHEME';

export const BASIS_BY_PORTFOLIO: Record<string, PricingBasis> = {
  // Priced from a sum assured and the version's base rate table.
  TERM: 'PREMIUM',
  WL: 'PREMIUM',
  END: 'PREMIUM',
  MB: 'PREMIUM',
  PAR: 'PREMIUM',
  // Priced from money paid in and a rate: a deposit's grid, or an account's declared rate.
  SAV: 'SAVINGS',
  DEP: 'SAVINGS',
  // Priced from the plan's own premium grid, a line per life -- quoted here, from the family entered above.
  FUN: 'FUNERAL',
  // Priced by its own fields on THIS form, which ask for what its own quoter needs -- an annuity form, a fund
  // split. Repeating their figures here would be two prices for one thing on one screen.
  IANN: 'OWN_SECTION',
  DANN: 'OWN_SECTION',
  PEN: 'OWN_SECTION',
  ULIP: 'OWN_SECTION',
  // Priced per scheme, not per life: the rate belongs to the scheme contract.
  CRL: 'PER_SCHEME',
  GRPL: 'PER_SCHEME',
};

/** An unknown or absent portfolio keeps the original behaviour: ask for a premium and show whatever comes back. */
export function pricingBasis(portfolioCode: string | null | undefined): PricingBasis {
  return (portfolioCode && BASIS_BY_PORTFOLIO[portfolioCode]) || 'PREMIUM';
}

/**
 * Whether the panel prices the product itself, and so can hand over a quotation (2026-10-20): a premium off a rate
 * table, a deposit or an account, or a funeral plan. The agents' calculator offers only these; annuities, unit-linked
 * and scheme cover are quoted by the office for now.
 */
export function quotesHere(portfolioCode: string | null | undefined): boolean {
  const basis = pricingBasis(portfolioCode);
  return basis === 'PREMIUM' || basis === 'SAVINGS' || basis === 'FUNERAL';
}

/** Where the server's quotation stands: not askable yet, asked, priced, or refused. */
export type QuoteState = 'incomplete' | 'loading' | 'quoted' | 'refused';

/** A prospect's rating facts as the calculator holds them: blank strings for what was not answered. */
export interface ProspectInputs {
  fullName: string;
  phoneNumber: string;
  dateOfBirth: string;
  sex: '' | 'FEMALE' | 'MALE';
  smokerStatus: '' | 'SMOKER' | 'NON_SMOKER' | 'UNKNOWN';
  occupationClass: string;
}
