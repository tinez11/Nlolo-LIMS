import { get, post, put } from '@/lib/http';
import type { ApiError } from '@/lib/apiError';
import type {
  ClaimView,
  CoveredLifeView,
  FuneralApplicationRequest,
  FuneralApplicationView,
  FuneralQuoteRequest,
  FuneralQuoteView,
  FuneralTermsView,
  IdentifyCoveredLife,
  PolicyView,
} from './types';

/**
 * Family funeral cover: a version's plans, a live quote of a family, a case's application, a policy's
 * covered lives and the claim-time steps. A 404 on a read is an answer -- "not a funeral plan" -- and is
 * normalised to null, as getAnnuityTerms does.
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

const enc = encodeURIComponent;

/** The plans, premium table and rules of a funeral version; null for any other version. */
export function getFuneralTerms(productId: string, versionId: string): Promise<FuneralTermsView | null> {
  return orNull(() => get<FuneralTermsView>(`/products/${enc(productId)}/versions/${enc(versionId)}/funeral`));
}

/** Prices a family on one plan without storing anything. A refusal is the quote's own 422. */
export function quoteFuneral(productId: string, versionId: string, body: FuneralQuoteRequest): Promise<FuneralQuoteView> {
  return post<FuneralQuoteView>(`/products/${enc(productId)}/versions/${enc(versionId)}/funeral-quote`, body);
}

export function getFuneralApplication(caseId: string): Promise<FuneralApplicationView | null> {
  return orNull(() => get<FuneralApplicationView>(`/underwriting/cases/${enc(caseId)}/funeral-application`));
}

export function recordFuneralApplication(caseId: string, body: FuneralApplicationRequest): Promise<FuneralApplicationView> {
  return put<FuneralApplicationView>(`/underwriting/cases/${enc(caseId)}/funeral-application`, body);
}

/** Every life on a funeral policy, main member first; an empty list for any other policy. */
export function getCoveredLives(policyNumber: string): Promise<CoveredLifeView[]> {
  return get<CoveredLifeView[]>(`/policies/${enc(policyNumber)}/covered-lives`);
}

export interface AddCoveredLifeBody {
  role: string;
  fullName: string;
  dateOfBirth: string;
  sex?: string | null;
  idNumber?: string | null;
  student?: boolean;
}

export function addCoveredLife(policyNumber: string, body: AddCoveredLifeBody): Promise<CoveredLifeView> {
  return post<CoveredLifeView>(`/policies/${enc(policyNumber)}/covered-lives`, body);
}

export function removeCoveredLife(policyNumber: string, coveredLifeId: string, reason: string): Promise<CoveredLifeView> {
  return post<CoveredLifeView>(`/policies/${enc(policyNumber)}/covered-lives/${enc(coveredLifeId)}/removal`,
    { reason: reason === '' ? null : reason });
}

export function promoteCoveredLife(policyNumber: string, coveredLifeId: string, body: IdentifyCoveredLife): Promise<CoveredLifeView> {
  return post<CoveredLifeView>(`/policies/${enc(policyNumber)}/covered-lives/${enc(coveredLifeId)}/promotion`, body);
}

export function takeOverFuneralPolicy(policyNumber: string, body: IdentifyCoveredLife): Promise<PolicyView> {
  return post<PolicyView>(`/policies/${enc(policyNumber)}/takeover`, body);
}

export function recordAccidentalDeath(claimId: string, accidental: boolean): Promise<ClaimView> {
  return put<ClaimView>(`/claims/${enc(claimId)}/accidental`, { accidental });
}
