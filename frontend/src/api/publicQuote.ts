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

// --- applying (2026-11-08, phase 2) ---------------------------------------------------------------------------------

type Schemas = import('@/types/api/omnichannel').components['schemas'];
export type PublicConsentWording = Schemas['PublicConsentWording'];
export type PublicConfirmedPhone = Schemas['PublicConfirmedPhone'];
export type PublicApplicationView = Schemas['PublicApplicationView'];
export type PublicApplyBody =
  NonNullable<paths['/public/applications']['post']['requestBody']>['content']['application/json'];

/** The declaration a visitor agrees to, in both languages, with the version the application records. */
export function getPublicConsent(): Promise<PublicConsentWording> {
  return get<PublicConsentWording>('/public/consent');
}

/** Texts a six-digit code to the phone; 422 with the reason, in the visitor's language, when it cannot. */
export function sendPublicCode(phoneNumber: string, language: 'EN' | 'SW', visitToken: string): Promise<void> {
  return post<void>('/public/phone-codes', { phoneNumber, language }, { headers: { 'X-Visit': visitToken } });
}

export function checkPublicCode(phoneNumber: string, code: string, language: 'EN' | 'SW',
                                visitToken: string): Promise<PublicConfirmedPhone> {
  return post<PublicConfirmedPhone>('/public/phone-codes/check', { phoneNumber, code, language },
    { headers: { 'X-Visit': visitToken } });
}

export function applyPublic(body: PublicApplyBody, visitToken: string) {
  return post<Schemas['CustomerApplication']>('/public/applications', body, { headers: { 'X-Visit': visitToken } });
}

/** For staff, on a case that came through the public link; 404 for any other case. */
export function getCasePublicApplication(caseId: string): Promise<PublicApplicationView> {
  return get<PublicApplicationView>(`/underwriting/cases/${encodeURIComponent(caseId)}/public-application`);
}
