import { get } from '@/lib/http';
import type { components } from '@/types/api/omnichannel';
import type { DocumentFormat } from './documents';

/**
 * A client's premiums across all their policies (2026-11-12, the client Premiums tab): every open invoice up to the
 * window asked for, overdue first; three figures summed on the server; the newest receipt across all their policies;
 * and the policies with nothing raised ahead. Staff with collections.view only.
 */
export type ClientPremiums = components['schemas']['ClientPremiums'];
export type ClientPremiumRow = components['schemas']['ClientPremiumRow'];
export type ClientNotInvoiced = components['schemas']['ClientNotInvoiced'];
export type PremiumWindow = ClientPremiums['window'];

const enc = encodeURIComponent;
const ACCEPT: Record<DocumentFormat, string> = {
  pdf: 'application/pdf',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
};

export function getClientPremiums(partyId: string, window: PremiumWindow): Promise<ClientPremiums> {
  return get<ClientPremiums>(`/parties/${enc(partyId)}/premiums`, { params: { window } });
}

export function downloadClientPremiums(partyId: string, window: PremiumWindow, format: DocumentFormat): Promise<Blob> {
  return get<Blob>(`/parties/${enc(partyId)}/premiums/${format}`,
    { params: { window }, responseType: 'blob', headers: { Accept: ACCEPT[format] } });
}
