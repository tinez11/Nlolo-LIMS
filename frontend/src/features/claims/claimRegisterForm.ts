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

/**
 * What the form has to know about the policy to validate a claim against it.
 *
 * <p>A factory rather than a constant because {@code policyMemberId} is required on a group
 * scheme and refused on individual business, and only the policy says which. The screen already
 * loads the policy detail to render its header, so the category is in hand without a new fetch.
 */
export interface RegisterClaimFormContext {
  /**
   * Undefined until the policy has loaded — treated as "not a scheme", so the field stays
   * hidden and no member is demanded for a question the form has not asked yet.
   *
   * <p>`| undefined` is explicit because this project compiles with
   * `exactOptionalPropertyTypes`, where an optional property and one that may hold `undefined`
   * are different types.
   */
  productCategory?: string | null | undefined;
}

export function registerClaimFormSchema(context: RegisterClaimFormContext = {}) {
  /**
   * Does this contract insure MANY lives, so that a claim on it must say which one died?
   *
   * <p><b>The third place this same question was asked by naming one category.</b> The page
   * decided whether to render the picker, this module decided whether to require it, and the
   * member roll decided which columns to show — each by testing GROUP_LIFE alone. A CREDIT_LIFE
   * scheme insures a lender's whole book and answers yes to all three, so every one of them was
   * wrong about it, and the failures compounded: the picker did not render, so no member was
   * chosen, so this rule then refused the claim for naming a member on a policy it believed had
   * none.
   *
   * <p>Phrased as the question rather than the category, because that is what the rule is about
   * and it is what the next scheme category will also answer yes to.
   */
  const insuresManyLives =
    context.productCategory === 'GROUP_LIFE' || context.productCategory === 'CREDIT_LIFE';
  return z
    .object({
      policyNumber: requiredText('Policy number is required').regex(
        POLICY_NUMBER_PATTERN,
        'Not a valid policy number',
      ),
      // Not required at this level: which rule applies depends on the policy, so it is
      // enforced in the refinement below rather than by the field's own schema.
      policyMemberId: z.string().trim(),
      claimantPartyId: requiredText('Claimant party id is required').regex(
        UUID_PATTERN,
        'Not a valid party id',
      ),
      dateOfEvent: isoDate('Date of event is required'),
      details: claimDetailsSchema,
    })
    .superRefine((values, ctx) => {
      // A scheme insures many lives, so a claim on one must say which. The server refuses it
      // too (409 from PolicyApi.claimableCover) -- this is here so the person filing finds out
      // while they are still looking at the form.
      if (insuresManyLives && values.policyMemberId === '') {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['policyMemberId'],
          message: 'Choose which member this claim is for',
        });
      }
      if (insuresManyLives && values.policyMemberId !== '' && !UUID_PATTERN.test(values.policyMemberId)) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['policyMemberId'],
          message: 'Not a valid member',
        });
      }
      // The mirror rule, unreachable through the UI because the field only renders for a
      // scheme -- validated anyway, because it belongs to the contract rather than to which
      // inputs happen to be on screen. A member id against an individual policy is somebody
      // who believes that contract has a schedule.
      if (!insuresManyLives && values.policyMemberId !== '') {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['policyMemberId'],
          message: 'This policy insures one life, so it has no members to name',
        });
      }
    });
}

export type RegisterClaimFormValues = z.infer<ReturnType<typeof registerClaimFormSchema>>;
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
    // Blank means "individual policy, no member". Sent as null rather than omitted so the
    // request says so explicitly instead of leaving the server to read absence as a choice.
    policyMemberId: values.policyMemberId === '' ? null : values.policyMemberId,
    claimantPartyId: values.claimantPartyId.trim(),
    // Derived from details, never a separate field -- see the module doc above.
    claimType: values.details.claimType,
    dateOfEvent: values.dateOfEvent,
    details: values.details,
  };
}
