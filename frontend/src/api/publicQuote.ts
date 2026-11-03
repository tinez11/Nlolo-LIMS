import { get, post } from '@/lib/http';
import type { paths } from '@/types/api/omnichannel';
import type { CustomerProduct, CustomerQuote, CustomerQuoteRequest } from './portal';

/**
 * The public quote link (2026-11-02): no login. A visit starts once the browser has passed Cloudflare Turnstile; the
 * visit token then rides every price request in X-Visit. Nothing here stores anything on the server.
 */
export type PublicPerson =
  NonNullable<paths['/public/products/{productId}/quote']['post']['requestBody']>['content']['application/json']['person'];

export interface PublicVisit {
  visitToken: string;
  minutes: number;
}

export function startPublicVisit(turnstileToken: string): Promise<PublicVisit> {
  return post<PublicVisit>('/public/visits', { turnstileToken });
}

export function getPublicProducts(): Promise<CustomerProduct[]> {
  return get<CustomerProduct[]>('/public/products');
}

export function quotePublicProduct(productId: string, person: PublicPerson, request: CustomerQuoteRequest,
                                   visitToken: string): Promise<CustomerQuote> {
  return post<CustomerQuote>(`/public/products/${encodeURIComponent(productId)}/quote`, { person, request },
    { headers: { 'X-Visit': visitToken } });
}
