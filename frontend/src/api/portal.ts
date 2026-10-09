import { del, get, post } from '@/lib/http';
import type { components, paths } from '@/types/api/omnichannel';

/**
 * The customer portal (2026-10-08, the customer portal design step 1): staff invite a client and manage their access;
 * the signed-in customer asks who they are.
 */

export type PortalAccessView = components['schemas']['PortalAccess'];
export type PortalInviteView = components['schemas']['PortalInvite'];
export type CustomerMe = paths['/customer/me']['get']['responses']['200']['content']['application/json'];

const enc = encodeURIComponent;

export function getPortalAccess(partyId: string): Promise<PortalAccessView> {
  return get<PortalAccessView>(`/parties/${enc(partyId)}/portal-access`);
}

export function invitePortal(partyId: string): Promise<PortalInviteView> {
  return post<PortalInviteView>(`/parties/${enc(partyId)}/portal-access`, {});
}

export function resendPortalInvite(partyId: string): Promise<PortalInviteView> {
  return post<PortalInviteView>(`/parties/${enc(partyId)}/portal-access/resend`, {});
}

export function revokePortal(partyId: string): Promise<PortalAccessView> {
  return del<PortalAccessView>(`/parties/${enc(partyId)}/portal-access`);
}

/** The signed-in customer's own record -- and the call that marks their invite used. */
export function getMe(): Promise<CustomerMe> {
  return get<CustomerMe>('/customer/me');
}

export type CustomerDashboardView = components['schemas']['CustomerDashboard'];
export type CustomerPolicySummary = components['schemas']['CustomerPolicySummary'];
export type CustomerPolicyView = components['schemas']['CustomerPolicy'];

/** Step 2: the customer's dashboard -- always their own, from the token. */
export function getCustomerDashboard(): Promise<CustomerDashboardView> {
  return get<CustomerDashboardView>('/customer/dashboard');
}

export type CustomerProduct = components['schemas']['CustomerProduct'];
export type CustomerQuote = components['schemas']['CustomerQuote'];
export type CustomerApplication = components['schemas']['CustomerApplication'];

/** Step 5: the products offered online. */
export function getCustomerProducts(): Promise<CustomerProduct[]> {
  return get<CustomerProduct[]>('/customer/products');
}

/** Step 5: an indicative price on the customer's own details -- not an offer. */
export function quoteCustomerProduct(
  productId: string,
  request: { sumAssured: number; frequency: 'MONTHLY' | 'QUARTERLY' | 'ANNUALLY' },
): Promise<CustomerQuote> {
  return post<CustomerQuote>(`/customer/products/${enc(productId)}/quote`, request);
}

/** Step 5: what the customer asked for, newest first. */
export function getCustomerApplications(): Promise<CustomerApplication[]> {
  return get<CustomerApplication[]>('/customer/applications');
}

/** Step 5: ask for a product -- one application per product while it is reviewed (409 for a second). */
export function applyForProduct(request: {
  productId: string; sumAssured: number | null; frequency: 'MONTHLY' | 'QUARTERLY' | 'ANNUALLY' | null;
}): Promise<CustomerApplication> {
  return post<CustomerApplication>('/customer/applications', request);
}

export type CustomerClaimSummary = components['schemas']['CustomerClaimSummary'];
export type CustomerClaimView = components['schemas']['CustomerClaim'];

/** Step 4: the claims the customer made, newest event first. */
export function getCustomerClaims(): Promise<CustomerClaimSummary[]> {
  return get<CustomerClaimSummary[]>('/customer/claims');
}

/** Step 4: one of the customer's claims in plain words; 403 for one somebody else made. */
export function getCustomerClaim(claimId: string): Promise<CustomerClaimView> {
  return get<CustomerClaimView>(`/customer/claims/${enc(claimId)}`);
}

export type CustomerMessage = components['schemas']['CustomerMessage'];

/** Step 7: what we sent the customer, newest first, once per message. */
export function getCustomerMessages(): Promise<CustomerMessage[]> {
  return get<CustomerMessage[]>('/customer/messages');
}

/** Step 7: opened in the portal. */
export function markMessageRead(messageId: string): Promise<CustomerMessage> {
  return post<CustomerMessage>(`/customer/messages/${enc(messageId)}/read`, {});
}

/** Step 2: one of the customer's own policies, customer-safe; 403 for one they do not hold. */
export function getCustomerPolicy(policyNumber: string): Promise<CustomerPolicyView> {
  return get<CustomerPolicyView>(`/customer/policies/${enc(policyNumber)}`);
}
