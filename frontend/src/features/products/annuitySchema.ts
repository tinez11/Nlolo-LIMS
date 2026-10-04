import { z } from 'zod';
import type { ProductCategory, ProductVersionSpec } from '@/api/types';
import { ISO_DATE_PATTERN } from '@/lib/patterns';
import {
  blankVestingFields,
  isDeferred,
  toVestingRequest,
  vestingFieldsShape,
  vestingWindow,
  type VestingFields,
} from './vestingSchema';

/**
 * An ANNUITY version's terms as the publish form holds them (product step 5), and
 * `AnnuityPlanValidator` rule for rule and message for message.
 *
 * A form's rates are a pasted grid, one row per line, rather than one input per cell: a version
 * accepting ages 55 to 80 on a joint BY_SEX form is several hundred cells, which nobody types into
 * boxes. The columns depend on the form -- see `rateColumns` -- and a line that does not parse is
 * refused by line number before any coverage check runs.
 */

export const ANNUITY_FREQUENCIES = ['MONTHLY', 'QUARTERLY', 'SEMI_ANNUAL', 'ANNUAL'] as const;

const annuityFormSchema = z.object({
  formCode: z.string().trim(),
  guaranteeYears: z.string().trim(),
  joint: z.boolean(),
  survivorPercent: z.string().trim(),
  escalationPercent: z.string().trim(),
  capitalProtected: z.boolean(),
  rateBasis: z.enum(['UNISEX', 'BY_SEX']),
  ratesText: z.string(),
});
export type AnnuityFormValues = z.infer<typeof annuityFormSchema>;

export const annuityFieldsShape = {
  annuityTiming: z.string().trim(),
  annuityProofOfLifeMonths: z.string().trim(),
  annuityJointDiffMin: z.string().trim(),
  annuityJointDiffMax: z.string().trim(),
  annuityBasisReference: z.string().trim(),
  annuityBasisDate: z
    .string()
    .trim()
    .refine((v) => v === '' || ISO_DATE_PATTERN.test(v), 'Not a valid date'),
  annuityForms: z.array(annuityFormSchema),
  /** One per frequency, in ANNUITY_FREQUENCIES order. A blank factor means the frequency is not offered. */
  annuityFactors: z.array(z.object({ frequency: z.enum(ANNUITY_FREQUENCIES), factor: z.string().trim() })),
  // A deferred annuity's vesting terms (D2); see vestingSchema.
  ...vestingFieldsShape,
};

export interface AnnuityFields extends Omit<VestingFields, 'annuityForms' | 'annuityFactors' | 'maxEntryAge'> {
  annuityTiming: string;
  annuityProofOfLifeMonths: string;
  annuityJointDiffMin: string;
  annuityJointDiffMax: string;
  annuityBasisReference: string;
  annuityBasisDate: string;
  annuityForms: AnnuityFormValues[];
  annuityFactors: { frequency: (typeof ANNUITY_FREQUENCIES)[number]; factor: string }[];
  minEntryAge: string;
  maxEntryAge: string;
}

export function blankAnnuityFields(): Omit<AnnuityFields, 'minEntryAge' | 'maxEntryAge'> {
  return {
    annuityTiming: '',
    annuityProofOfLifeMonths: '12',
    annuityJointDiffMin: '',
    annuityJointDiffMax: '',
    annuityBasisReference: '',
    annuityBasisDate: '',
    annuityForms: [],
    // ANNUAL is offered at 1 by default -- it is the grid's own frequency; the rest start unoffered.
    annuityFactors: ANNUITY_FREQUENCIES.map((frequency) => ({ frequency, factor: frequency === 'ANNUAL' ? '1' : '' })),
    ...blankVestingFields(),
  };
}

export function blankAnnuityForm(): AnnuityFormValues {
  return {
    formCode: '',
    guaranteeYears: '0',
    joint: false,
    survivorPercent: '',
    escalationPercent: '0',
    capitalProtected: false,
    rateBasis: 'UNISEX',
    ratesText: '',
  };
}

/** The columns one line of a form's rate grid carries, by whether it is joint and whether it is BY_SEX. */
export function rateColumns(form: Pick<AnnuityFormValues, 'joint' | 'rateBasis'>): string[] {
  return [
    ...(form.rateBasis === 'BY_SEX' ? ['sex'] : []),
    'age',
    ...(form.joint ? ['difference from', 'difference to'] : []),
    'rate per 1,000',
  ];
}

export interface ParsedRate {
  sex: 'FEMALE' | 'MALE' | null;
  age: number;
  ageDifferenceFrom: number | null;
  ageDifferenceTo: number | null;
  annualRatePerMille: number;
}

/** A form's grid, line by line; `error` names the first line that does not parse. */
export function parseRates(form: AnnuityFormValues): { rates: ParsedRate[]; error?: string } {
  const columns = rateColumns(form);
  const rates: ParsedRate[] = [];
  const lines = form.ratesText.split(/\r?\n/);
  for (let n = 0; n < lines.length; n++) {
    const line = lines[n]!.trim();
    if (line === '') continue;
    const cells = line.split(/[,\t;]\s*|\s+/).filter((c) => c !== '');
    const bad = `Form ${form.formCode}, line ${n + 1}: expected ${columns.join(', ')}`;
    if (cells.length !== columns.length) return { rates, error: bad };
    let i = 0;
    let sex: ParsedRate['sex'] = null;
    if (form.rateBasis === 'BY_SEX') {
      const s = cells[i++]!.toUpperCase();
      if (s !== 'FEMALE' && s !== 'MALE' && s !== 'F' && s !== 'M') return { rates, error: bad };
      sex = s.startsWith('F') ? 'FEMALE' : 'MALE';
    }
    const age = Number(cells[i++]);
    const from = form.joint ? Number(cells[i++]) : null;
    const to = form.joint ? Number(cells[i++]) : null;
    const rate = Number(cells[i]);
    if (!Number.isInteger(age) || (from !== null && !Number.isInteger(from)) || (to !== null && !Number.isInteger(to))
        || Number.isNaN(rate)) {
      return { rates, error: bad };
    }
    rates.push({ sex, age, ageDifferenceFrom: from, ageDifferenceTo: to, annualRatePerMille: rate });
  }
  return { rates };
}

const isNumber = (v: string) => v !== '' && !Number.isNaN(Number(v));

/** Anything authored in the annuity section -- refused outright on any other category. */
function hasAnnuityTerms(v: AnnuityFields): boolean {
  return v.annuityForms.length > 0 || v.annuityTiming !== '' || v.annuityBasisReference !== '';
}

/** `AnnuityPlanValidator.validate`, after the exclusions the other validators already refuse in their own words. */
export function validateAnnuity(category: ProductCategory, v: AnnuityFields, ctx: z.RefinementCtx) {
  const issue = (path: (string | number)[], message: string) => ctx.addIssue({ code: 'custom', path, message });
  if (category !== 'ANNUITY') {
    if (hasAnnuityTerms(v)) issue(['annuityForms'], 'Annuity terms are only for an ANNUITY product');
    return;
  }
  const offered = v.annuityFactors.filter((f) => f.factor !== '');
  // checkTerms, in its order.
  if (v.annuityForms.length === 0) issue(['annuityForms'], 'An annuity version needs at least one annuity form');
  if (offered.length === 0) issue(['annuityFactors'], 'An annuity version needs at least one payment frequency');
  if (v.annuityTiming !== 'ARREARS' && v.annuityTiming !== 'ADVANCE') {
    issue(['annuityTiming'], 'An annuity version must state whether income is paid in ARREARS or in ADVANCE');
  }
  if (v.annuityBasisReference === '' || v.annuityBasisDate === '') {
    issue([v.annuityBasisReference === '' ? 'annuityBasisReference' : 'annuityBasisDate'],
      'An annuity rate table needs the actuarial basis it was issued under');
  }
  const pol = Number(v.annuityProofOfLifeMonths);
  if (!Number.isInteger(pol) || pol < 1 || pol > 24) {
    issue(['annuityProofOfLifeMonths'], "An annuity's proof-of-life interval must be between 1 and 24 months");
  }
  const bounded = v.minEntryAge !== '' && v.maxEntryAge !== '';
  if (!bounded) {
    issue(['minEntryAge'], 'An annuity version needs minimum and maximum entry ages, so its grids can be checked for gaps');
  }

  // checkSettings, and duplicate codes.
  const codes = new Set<string>();
  v.annuityForms.forEach((form, i) => {
    const f = `Form ${form.formCode}: `;
    const at = (field: string) => ['annuityForms', i, field];
    if (form.formCode === '') issue(at('formCode'), 'Give the form a code');
    const g = Number(form.guaranteeYears);
    if (!Number.isInteger(g) || g < 0 || g > 30) issue(at('guaranteeYears'), f + 'a guaranteed period must be between 0 and 30 years');
    if (!form.joint && form.survivorPercent !== '') {
      issue(at('survivorPercent'), f + 'a survivor percentage is only for a joint-life form');
    }
    if (form.joint && (!isNumber(form.survivorPercent) || Number(form.survivorPercent) < 1 || Number(form.survivorPercent) > 100)) {
      issue(at('survivorPercent'), f + 'a joint-life form needs a survivor percentage between 1 and 100');
    }
    if (!isNumber(form.escalationPercent) || Number(form.escalationPercent) < 0 || Number(form.escalationPercent) > 10) {
      issue(at('escalationPercent'), f + 'escalation must be between 0% and 10% a year');
    }
    if (form.formCode !== '' && codes.has(form.formCode)) {
      issue(at('formCode'), `Form code ${form.formCode} appears more than once`);
    }
    codes.add(form.formCode);
  });

  // checkDistinctSettings.
  const settings = (f: AnnuityFormValues) =>
    [Number(f.guaranteeYears), f.joint, f.survivorPercent === '' ? '' : Number(f.survivorPercent),
      Number(f.escalationPercent), f.capitalProtected, f.rateBasis].join('|');
  for (let i = 0; i < v.annuityForms.length; i++) {
    for (let j = i + 1; j < v.annuityForms.length; j++) {
      if (settings(v.annuityForms[i]!) === settings(v.annuityForms[j]!)) {
        issue(['annuityForms', j, 'formCode'],
          `Forms ${v.annuityForms[i]!.formCode} and ${v.annuityForms[j]!.formCode} have the same settings`);
      }
    }
  }

  const anyJoint = v.annuityForms.some((f) => f.joint);
  const diffMin = Number(v.annuityJointDiffMin);
  const diffMax = Number(v.annuityJointDiffMax);
  const diffsSet = v.annuityJointDiffMin !== '' && v.annuityJointDiffMax !== ''
    && Number.isInteger(diffMin) && Number.isInteger(diffMax);
  if (anyJoint && !diffsSet) {
    issue(['annuityJointDiffMin'], "A joint-life form needs the version's range of age differences");
  }

  // checkCoverage: the first gap per form, which is the one the server would name. An immediate
  // annuity is priced at purchase, so its grid covers the entry ages; a deferred one (D2) is priced at
  // vesting, so it covers the vesting window -- and a malformed window is the vesting rule's to name.
  const deferred = isDeferred(category, v);
  const window = deferred ? vestingWindow(v) : null;
  if (deferred ? window !== null : bounded) {
    const minAge = window ? window[0] : Number(v.minEntryAge);
    const maxAge = window ? window[1] : Number(v.maxEntryAge);
    v.annuityForms.forEach((form, i) => {
      const path = ['annuityForms', i, 'ratesText'];
      const { rates, error } = parseRates(form);
      if (error) {
        issue(path, error);
        return;
      }
      const message = coverageGap(form, rates, minAge, maxAge, anyJoint && diffsSet ? [diffMin, diffMax] : null);
      if (message) issue(path, message);
    });
  }

  // checkFrequencies.
  offered.forEach((f) => {
    const factor = Number(f.factor);
    const path = ['annuityFactors', ANNUITY_FREQUENCIES.indexOf(f.frequency), 'factor'];
    if (Number.isNaN(factor) || factor <= 0 || factor > 1) {
      issue(path, 'A frequency factor must be greater than 0 and at most 1');
    } else if (f.frequency === 'ANNUAL' && factor !== 1) {
      issue(path, 'The ANNUAL frequency factor is 1');
    }
  });
}

function coverageGap(form: AnnuityFormValues, rates: ParsedRate[], minAge: number, maxAge: number,
                     diffs: [number, number] | null): string | null {
  const code = form.formCode;
  for (const r of rates) {
    if (form.rateBasis === 'UNISEX' && r.sex !== null) return `Form ${code}: a UNISEX form's rates carry no sex`;
    if (form.rateBasis === 'BY_SEX' && r.sex === null) return `Form ${code}: a BY_SEX form's rates each name a sex`;
    if (!(r.annualRatePerMille > 0)) return `Form ${code}: every rate must be greater than zero`;
  }
  const sexes: ('FEMALE' | 'MALE' | null)[] = form.rateBasis === 'BY_SEX' ? ['FEMALE', 'MALE'] : [null];
  for (const sex of sexes) {
    for (let age = minAge; age <= maxAge; age++) {
      const atAge = rates.filter((r) => r.age === age && r.sex === sex);
      if (!form.joint) {
        if (atAge.length === 0) {
          return sex === null ? `Form ${code} has no rate for age ${age}` : `Form ${code} has no rate for a ${sex} aged ${age}`;
        }
        continue;
      }
      if (!diffs) return null; // the missing range is already reported
      for (let d = diffs[0]; d <= diffs[1]; d++) {
        const hits = atAge.filter((r) => r.ageDifferenceFrom !== null && r.ageDifferenceTo !== null
          && r.ageDifferenceFrom <= d && d <= r.ageDifferenceTo).length;
        if (hits > 1) return `Form ${code} has overlapping age-difference bands at age ${age}`;
        if (hits === 0) return `Form ${code} has no rate for age ${age} with an age difference of ${d}`;
      }
    }
  }
  return null;
}

/** The `annuity` block -- sent only on an ANNUITY product, where it is required. */
export function toAnnuityRequest(v: AnnuityFields): NonNullable<ProductVersionSpec['annuity']> {
  const anyJoint = v.annuityForms.some((f) => f.joint);
  return {
    timing: v.annuityTiming as 'ARREARS' | 'ADVANCE',
    proofOfLifeIntervalMonths: Number(v.annuityProofOfLifeMonths),
    jointAgeDifferenceMin: anyJoint ? Number(v.annuityJointDiffMin) : null,
    jointAgeDifferenceMax: anyJoint ? Number(v.annuityJointDiffMax) : null,
    basisReference: v.annuityBasisReference,
    basisDate: v.annuityBasisDate,
    forms: v.annuityForms.map((form) => ({
      formCode: form.formCode,
      guaranteeYears: Number(form.guaranteeYears),
      joint: form.joint,
      survivorPercent: form.joint ? Number(form.survivorPercent) : null,
      escalationPercent: Number(form.escalationPercent),
      capitalProtected: form.capitalProtected,
      rateBasis: form.rateBasis,
      rates: parseRates(form).rates,
    })),
    frequencies: v.annuityFactors
      .filter((f) => f.factor !== '')
      .map((f) => ({ frequency: f.frequency, factor: Number(f.factor) })),
    // Sent only on a deferred annuity; its absence is what makes a version immediate.
    vesting: v.annuityKind === 'DEFERRED' ? toVestingRequest(v) : null,
  };
}
