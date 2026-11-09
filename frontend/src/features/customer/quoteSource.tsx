import { createContext, useContext, type ReactNode } from 'react';
import { getCustomerProducts, quoteCustomerProduct, type CustomerProduct, type CustomerQuote, type CustomerQuoteRequest } from '@/api/portal';

/**
 * Where the Get-cover and price screens read from (2026-11-02): the signed-in customer's own portal, or the public
 * quote link, where a visitor with no login prices on the facts they typed. The screens are the same; what differs is
 * whose facts price it, where its links go, and whether it can be applied for there.
 */
export interface QuoteSource {
  /** Where the product list lives; a product's price page is `${base}/${productId}`. */
  base: string;
  listProducts: () => Promise<CustomerProduct[]>;
  quote: (productId: string, request: CustomerQuoteRequest) => Promise<CustomerQuote>;
  /** Both apply from the price (the public link since 2026-11-08). */
  canApply: boolean;
  /**
   * Set on the public link: applying there confirms the visitor's phone by a texted code and asks them to agree the
   * declaration, and the visit carries every request. Absent in the portal, where the customer is signed in.
   */
  publicApply?: PublicApplyContext;
  /** The portal lists what an adviser prices, with a way to ask; the public link, with nobody to call back, does not. */
  showsAdviserProducts: boolean;
  /** Shown above the product list -- the public link's "who you are" summary. */
  header?: ReactNode;
}

export interface PublicApplyContext {
  visitToken: string;
  /** What the visitor told us before pricing -- the price was worked out on these. */
  person: { fullName: string; dateOfBirth: string; sex: 'FEMALE' | 'MALE' | null; smokerStatus: 'NON_SMOKER' | 'SMOKER' | 'UNKNOWN' | null; phoneNumber: string };
}

export const PORTAL_SOURCE: QuoteSource = {
  base: '/customers/products',
  listProducts: getCustomerProducts,
  quote: quoteCustomerProduct,
  canApply: true,
  showsAdviserProducts: true,
};

const QuoteSourceContext = createContext<QuoteSource>(PORTAL_SOURCE);

export const QuoteSourceProvider = QuoteSourceContext.Provider;

export function useQuoteSource(): QuoteSource {
  return useContext(QuoteSourceContext);
}
