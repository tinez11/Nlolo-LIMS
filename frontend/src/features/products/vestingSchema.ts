import { z } from 'zod';
import type { ProductCategory, VestingTermsSpec } from '@/api/types';

/**
 * A deferred annuity's vesting terms as the publish form holds them (product step 5 D2), and
 * `VestingPlanValidator` rule for rule and message for message.
 *
 * A deferred annuity is an ANNUITY version that saves in an account first: choosing "Deferred" on
 * the form turns the account section on, so the two server rules that pair vesting terms with an
 * account ("only for an annuity that saves in an account", "must state its vesting terms") cannot be
 * broken from here and are not mirrored.
 */

export const vestingFieldsShape = {
  /** IMMEDIATE (D1, bought with a single premium) or DEFERRED (saves in an account, then vests). */
  annuityKind: z.string().trim(),
  vestingMinAge: z.string().trim(),
  vestingMaxAge: z.string().trim(),
  vestingDefaultForm: z.string().trim(),
  vestingDefaultFrequency: z.string().trim(),
  vestingCap: z.string().trim(),
  /** '' until chosen -- no default, on purpose (spec Q9): YES locks it before vesting, NO does not. */
  vestingLocked: z.string().trim(),
};

export interface VestingFields {
  annuityKind: string;
  vestingMinAge: string;
  vestingMaxAge: string;
  vestingDefaultForm: string;
  vestingDefaultFrequency: string;
  vestingCap: string;
  vestingLocked: string;
  maxEntryAge: string;
  annuityForms: { formCode: string; joint: boolean }[];
  annuityFactors: { frequency: string; factor: string }[];
}

export function blankVestingFields() {
  return {
    annuityKind: 'IMMEDIATE',
    vestingMinAge: '',
    vestingMaxAge: '',
    vestingDefaultForm: '',
    vestingDefaultFrequency: '',
    vestingCap: '',
    vestingLocked: '',
  };
}

export function isDeferred(category: ProductCategory, v: Pick<VestingFields, 'annuityKind'>): boolean {
  return category === 'ANNUITY' && v.annuityKind === 'DEFERRED';
}

const isInt = (v: string) => v !== '' && Number.isInteger(Number(v));

/** The vesting window the grids must cover, when it is a valid one; null otherwise (the window rule names it). */
export function vestingWindow(v: Pick<VestingFields, 'vestingMinAge' | 'vestingMaxAge'>): [number, number] | null {
  if (!isInt(v.vestingMinAge) || !isInt(v.vestingMaxAge)) return null;
  const min = Number(v.vestingMinAge);
  const max = Number(v.vestingMaxAge);
  return min >= 0 && max <= 120 && min <= max ? [min, max] : null;
}

/** `VestingPlanValidator.validate`, on a deferred annuity only. */
export function validateVesting(category: ProductCategory, v: VestingFields, ctx: z.RefinementCtx) {
  if (!isDeferred(category, v)) return;
  const issue = (path: string[], message: string) => ctx.addIssue({ code: 'custom', path, message });
  if (vestingWindow(v) === null) {
    issue(['vestingMinAge'], 'The vesting window runs from a minimum to a maximum vesting age, each between 0 and 120');
  }
  const defaultForm = v.annuityForms.find((f) => f.formCode !== '' && f.formCode === v.vestingDefaultForm);
  if (!defaultForm) {
    issue(['vestingDefaultForm'], `The default form ${v.vestingDefaultForm} is not one of this version's forms`);
  } else if (defaultForm.joint) {
    issue(['vestingDefaultForm'],
      `The default form ${defaultForm.formCode} is joint-life; a pension that vests with no instruction vests on one life`);
  }
  const offered = v.annuityFactors.some((f) => f.factor !== '' && f.frequency === v.vestingDefaultFrequency);
  if (!offered) {
    issue(['vestingDefaultFrequency'], `The default frequency ${v.vestingDefaultFrequency} is not one of this version's frequencies`);
  }
  const cap = Number(v.vestingCap);
  if (v.vestingCap === '' || Number.isNaN(cap) || cap < 0 || cap > 100) {
    issue(['vestingCap'], 'The lump-sum cap must be between 0% and 100% of the balance');
  }
  if (v.vestingLocked !== 'YES' && v.vestingLocked !== 'NO') {
    issue(['vestingLocked'], 'A deferred annuity must state whether it can be surrendered before it vests');
  }
  if (isInt(v.maxEntryAge) && isInt(v.vestingMaxAge) && Number(v.maxEntryAge) >= Number(v.vestingMaxAge)) {
    issue(['vestingMaxAge'],
      'The maximum entry age must be below the maximum vesting age, so every customer can reach a vesting age');
  }
}

/** The `annuity.vesting` block -- sent only on a deferred annuity. */
export function toVestingRequest(v: VestingFields): VestingTermsSpec {
  return {
    minVestingAge: Number(v.vestingMinAge),
    maxVestingAge: Number(v.vestingMaxAge),
    defaultFormCode: v.vestingDefaultForm,
    defaultFrequency: v.vestingDefaultFrequency as NonNullable<VestingTermsSpec['defaultFrequency']>,
    maxCommutationPercent: Number(v.vestingCap),
    surrenderBeforeVesting: v.vestingLocked === 'NO',
  };
}
