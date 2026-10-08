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
