import type { RegisterClaimRequest } from '@/api/types';

/**
 * Reporting a claim from the customer portal (2026-10-08, the customer portal design step 4; PRD §17-18). A customer
 * says what happened in their own words; the clinical fields an assessor works from (ICD code, impairment) are not
 * theirs to know, so they go as "not known" and the assessor establishes them from the documents.
 */

export type CustomerClaimType = 'DEATH' | 'DISABILITY' | 'CRITICAL_ILLNESS' | 'MATURITY';

export interface CustomerClaimForm {
  policyNumber: string;
  /** Family funeral cover: who died. Empty on any other policy. */
  coveredLifeId: string;
  claimType: CustomerClaimType;
  /** ISO yyyy-MM-dd: the date of death, of onset, of diagnosis or of maturity. */
  dateOfEvent: string;
  /** Cause of death, the disability, or the diagnosis -- in the customer's words. */
  whatHappened: string;
  placeOfDeath: string;
  doctor: string;
  permanent: boolean;
  accidental: boolean;
}

export const EMPTY_CLAIM_FORM: CustomerClaimForm = {
  policyNumber: '', coveredLifeId: '', claimType: 'DEATH', dateOfEvent: '', whatHappened: '', placeOfDeath: '',
  doctor: '', permanent: false, accidental: false,
};

const NOT_KNOWN = 'Not known';

/** What still stops the form being sent, in the order the customer meets the fields; empty when it can go. */
export function missingForClaim(form: CustomerClaimForm, funeral: boolean): string[] {
  const missing: string[] = [];
  if (!form.policyNumber) missing.push('Choose the policy');
  if (funeral && !form.coveredLifeId) missing.push('Choose who died');
  if (!form.dateOfEvent) missing.push(form.claimType === 'MATURITY' ? 'Give the maturity date' : 'Give the date');
  if (form.claimType !== 'MATURITY' && !form.whatHappened.trim()) missing.push('Say what happened');
  if (form.claimType === 'DEATH' && !form.placeOfDeath.trim()) missing.push('Say where it happened');
  return missing;
}

/** The `POST /claims` body. `claimantPartyId` is the customer's own -- the server refuses anything else. */
export function toClaimRequest(form: CustomerClaimForm, claimantPartyId: string, funeral: boolean): RegisterClaimRequest {
  const text = form.whatHappened.trim();
  const base = {
    policyNumber: form.policyNumber,
    claimantPartyId,
    claimType: form.claimType,
    dateOfEvent: form.dateOfEvent,
    ...(funeral ? { coveredLifeId: form.coveredLifeId, accidental: form.accidental } : {}),
  };
  switch (form.claimType) {
    case 'DEATH':
      return { ...base, details: { claimType: 'DEATH', causeOfDeath: text, placeOfDeath: form.placeOfDeath.trim(),
        dateOfDeath: form.dateOfEvent, attendingPhysician: form.doctor.trim() || NOT_KNOWN } };
    case 'DISABILITY':
      // The degree of impairment is the assessor's finding, not the claimant's -- "0" records that none is claimed yet.
      return { ...base, details: { claimType: 'DISABILITY', disabilityType: text, onsetDate: form.dateOfEvent,
        permanent: form.permanent, impairmentPercent: '0' } };
    case 'CRITICAL_ILLNESS':
      return { ...base, details: { claimType: 'CRITICAL_ILLNESS', diagnosis: text, diagnosisDate: form.dateOfEvent,
        icdCode: NOT_KNOWN } };
    case 'MATURITY':
      return { ...base, details: { claimType: 'MATURITY', maturityDate: form.dateOfEvent } };
  }
}
