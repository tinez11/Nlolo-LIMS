import { z } from 'zod';
import type { RegisterClaimRequest } from '@/api/types';
import { ISO_DATE_PATTERN, POLICY_NUMBER_PATTERN, UUID_PATTERN } from '@/lib/patterns';

/**
 * Zod schema for the claim registration form, mirroring `ClaimsApiImpl` and the
 * sealed `ClaimDetails` Java records exactly, transcribed from the source rather
 * than guessed from the OpenAPI spec (which itself has no `discriminator`).
 *
 * The most important property this schema enforces BY CONSTRUCTION, not by a
 * runtime check: `Claim`'s own constructor 422s if the top-level `claimType`
 * disagrees with `details.claimType`. A `z.discriminatedUnion` on `details`
 * means there is only ever one `claimType` value in the parsed form -- the
 * top-level one is *derived* from it in `toApiRequest`, never entered separately,
 * so the two fields cannot drift apart from this form.
 */

const requiredText = (message: string) => z.string().trim().min(1, message);
const isoDate = (message: string) => requiredText(message).regex(ISO_DATE_PATTERN, message);

const deathDetailsSchema = z.object({
  claimType: z.literal('DEATH'),
  causeOfDeath: requiredText('Cause of death is required'),
  placeOfDeath: requiredText('Place of death is required'),
  dateOfDeath: isoDate('Date of death is required'),
  attendingPhysician: requiredText('Attending physician is required'),
});

// impairmentPercent mirrors DisabilityClaimDetails.impairmentPercent's exact wire
// pattern (^\d+(\.\d{1,2})?$) -- deliberately no leading '-', unlike Money: an
// impairment cannot be negative. Sent and stored as a STRING, not a number.
const IMPAIRMENT_PERCENT_PATTERN = /^\d+(\.\d{1,2})?$/;

const disabilityDetailsSchema = z.object({
  claimType: z.literal('DISABILITY'),
  disabilityType: requiredText('Disability type is required'),
  onsetDate: isoDate('Onset date is required'),
  permanent: z.boolean(),
  impairmentPercent: z
    .string()
    .regex(IMPAIRMENT_PERCENT_PATTERN, 'Must be a percentage like 62.50')
    .refine((v) => Number(v) <= 100, 'Cannot exceed 100'),
});

const criticalIllnessDetailsSchema = z.object({
  claimType: z.literal('CRITICAL_ILLNESS'),
  diagnosis: requiredText('Diagnosis is required'),
  diagnosisDate: isoDate('Diagnosis date is required'),
  icdCode: requiredText('ICD code is required'),
});

// The thinnest variant, deliberately: MaturityClaimDetails carries only
// maturityDate. This is also the one claimType decideSettlement lets skip the
// "requires at least one assessment" gate entirely (an auto-approvable path).
const maturityDetailsSchema = z.object({
  claimType: z.literal('MATURITY'),
  maturityDate: isoDate('Maturity date is required'),
});

const claimDetailsSchema = z.discriminatedUnion('claimType', [
  deathDetailsSchema,
  disabilityDetailsSchema,
  criticalIllnessDetailsSchema,
  maturityDetailsSchema,
]);

export const registerClaimFormSchema = z.object({
  policyNumber: requiredText('Policy number is required').regex(
    POLICY_NUMBER_PATTERN,
    'Not a valid policy number',
  ),
  claimantPartyId: requiredText('Claimant party id is required').regex(
    UUID_PATTERN,
    'Not a valid party id',
  ),
  dateOfEvent: isoDate('Date of event is required'),
  details: claimDetailsSchema,
});

export type RegisterClaimFormValues = z.infer<typeof registerClaimFormSchema>;
export type ClaimDetailsFormValues = RegisterClaimFormValues['details'];

/** Blank starting values per claim type, for switching the type selector. */
export function blankDetailsFor(claimType: ClaimDetailsFormValues['claimType']): ClaimDetailsFormValues {
  switch (claimType) {
    case 'DEATH':
      return { claimType, causeOfDeath: '', placeOfDeath: '', dateOfDeath: '', attendingPhysician: '' };
    case 'DISABILITY':
      return { claimType, disabilityType: '', onsetDate: '', permanent: false, impairmentPercent: '' };
    case 'CRITICAL_ILLNESS':
      return { claimType, diagnosis: '', diagnosisDate: '', icdCode: '' };
    case 'MATURITY':
      return { claimType, maturityDate: '' };
  }
}

/** Convert validated form values into exactly what `POST /claims` expects. */
export function toApiRequest(values: RegisterClaimFormValues): RegisterClaimRequest {
  return {
    policyNumber: values.policyNumber.trim(),
    claimantPartyId: values.claimantPartyId.trim(),
    // Derived from details, never a separate field -- see the module doc above.
    claimType: values.details.claimType,
    dateOfEvent: values.dateOfEvent,
    details: values.details,
  };
}
