import { del, get, post } from '@/lib/http';
import type { components } from '@/types/api/omnichannel';
import type { QuotationRequest } from './documents';

/**
 * The agents' prospect-to-proposal flow (2026-10-20). Quoting stores nothing; these are the writes that follow the
 * customer's agreement: save the accepted quotation, discard it, and submit it as a proposal. Inputs only -- every
 * figure is priced again on the server.
 */
export type AgentQuotation = components['schemas']['AgentQuotation'];

const enc = encodeURIComponent;

/** The customer agrees: the first write of the flow. */
export function acceptQuotation(request: QuotationRequest): Promise<AgentQuotation> {
  return post<AgentQuotation>('/agent-quotations', request);
}

export function getAgentQuotation(quotationId: string): Promise<AgentQuotation> {
  return get<AgentQuotation>(`/agent-quotations/${enc(quotationId)}`);
}

/** The customer did not go ahead. Refused once submitted. */
export function discardAgentQuotation(quotationId: string): Promise<void> {
  return del<void>(`/agent-quotations/${enc(quotationId)}`);
}

/** Opens the underwriter's case from the saved quotation, for the registered customer. */
export function submitProposal(quotationId: string, applicantPartyId: string): Promise<AgentQuotation> {
  return post<AgentQuotation>(`/agent-quotations/${enc(quotationId)}/proposal`, { applicantPartyId });
}
