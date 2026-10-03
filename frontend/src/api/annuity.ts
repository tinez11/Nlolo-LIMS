import { get, post, put } from '@/lib/http';
import type { ApiError } from '@/lib/apiError';
import type { AnnuityChoiceView, AnnuityContractView, AnnuityQuoteRequest, AnnuityQuoteView, AnnuityTermsView } from './types';

/**
 * Immediate annuities (product step 5): a version's forms, a live quote, a case's choice, and a
 * policy's contract. Every 404 here is an answer, not an error -- normalised to null, as
 * getPolicyBonuses and getAccount do.
 */

function isNotFound(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as ApiError).kind === 'notFound';
}

async function orNull<T>(load: () => Promise<T>): Promise<T | null> {
  try {
    return await load();
  } catch (error) {
    if (isNotFound(error)) return null;
    throw error;
  }
}

/** The forms and frequencies an annuity version offers; null for any other version. */
export function getAnnuityTerms(productId: string, versionId: string): Promise<AnnuityTermsView | null> {
  return orNull(() =>
    get<AnnuityTermsView>(`/products/${encodeURIComponent(productId)}/versions/${encodeURIComponent(versionId)}/annuity`),
  );
}

/** Prices a purchase without storing anything. A refusal is the pricer's own 422. */
export function quoteAnnuity(body: AnnuityQuoteRequest): Promise<AnnuityQuoteView> {
  return post<AnnuityQuoteView>('/annuity-quotes', body);
}

/** A policy's annuity contract, or null for a policy that is not one. */
export function getPolicyAnnuity(policyNumber: string): Promise<AnnuityContractView | null> {
  return orNull(() => get<AnnuityContractView>(`/policies/${encodeURIComponent(policyNumber)}/annuity`));
}

export interface AnnuityChoiceBody {
  formCode: string;
  frequency: string;
  jointLifePartyId?: string | null;
}

export function getAnnuityChoice(caseId: string): Promise<AnnuityChoiceView | null> {
  return orNull(() => get<AnnuityChoiceView>(`/underwriting/cases/${encodeURIComponent(caseId)}/annuity-choice`));
}

export function recordAnnuityChoice(caseId: string, body: AnnuityChoiceBody): Promise<AnnuityChoiceView> {
  return put<AnnuityChoiceView>(`/underwriting/cases/${encodeURIComponent(caseId)}/annuity-choice`, body);
}
