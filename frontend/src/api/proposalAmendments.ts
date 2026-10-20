import { get, post, put } from '@/lib/http';
import type { components } from '@/types/api/underwriting';
import type { components as partyComponents } from '@/types/api/party';
import type { UnderwritingCaseView } from './types';

/**
 * Returned for amendment, amended, resubmitted (2026-10-20). The state machine is the server's: an underwriter returns
 * an undecided proposal with reasons; the agent amends and resubmits a returned or postponed one; a decline is final.
 */
export type ReturnReason = components['schemas']['ReturnReason'];
export type ReturnCategory = ReturnReason['category'];
export type CaseEvent = components['schemas']['CaseEvent'];
export type NeedsAction = components['schemas']['NeedsAction'];
export type CaseQuotation = components['schemas']['CaseQuotation'];
export type ApplicantCorrection = partyComponents['schemas']['RegisterIndividualRequest'];

export interface TermsAmendment {
  sumAssured: number;
  requestedTermMonths: number | null;
  premiumPayingTermMonths: number | null;
  premiumFrequency: 'MONTHLY' | 'QUARTERLY' | 'ANNUALLY' | 'SINGLE' | null;
  proposedCommencementDate: string | null;
}

const enc = encodeURIComponent;

export function returnForAmendment(caseId: string, reasons: ReturnReason[], note: string | null): Promise<UnderwritingCaseView> {
  return post<UnderwritingCaseView>(`/underwriting/cases/${enc(caseId)}/return`, { reasons, note });
}

export function amendTerms(caseId: string, terms: TermsAmendment): Promise<UnderwritingCaseView> {
  return put<UnderwritingCaseView>(`/underwriting/cases/${enc(caseId)}/terms`, terms);
}

export function amendApplicant(caseId: string, corrected: ApplicantCorrection): Promise<unknown> {
  return put<unknown>(`/underwriting/cases/${enc(caseId)}/applicant`, corrected);
}

export function resubmitProposal(caseId: string, note: string | null): Promise<UnderwritingCaseView> {
  return post<UnderwritingCaseView>(`/underwriting/cases/${enc(caseId)}/resubmit`, { note });
}

export function getCaseHistory(caseId: string): Promise<CaseEvent[]> {
  return get<CaseEvent[]>(`/underwriting/cases/${enc(caseId)}/history`);
}

export function getNeedsAction(): Promise<NeedsAction[]> {
  return get<NeedsAction[]>('/underwriting/needs-action');
}

/** The quotation a proposal was opened from; 404 for a case opened any other way. */
export function getCaseQuotation(caseId: string): Promise<CaseQuotation> {
  return get<CaseQuotation>(`/underwriting/cases/${enc(caseId)}/quotation`);
}

/** The categories in the user's words (2026-10-20), in the order the underwriter picks from. */
export const RETURN_CATEGORY_LABELS: Record<ReturnCategory, string> = {
  PERSONAL_DETAILS: 'Wrong personal details',
  DOCUMENTS: 'Missing or unclear documents',
  COVER_OR_TERM: 'Cover amount or term',
  DISCLOSURES: 'Missing health answers',
  BENEFICIARIES: 'Beneficiaries',
  WRONG_PRODUCT: 'Wrong product',
  OTHER: 'Other',
};
