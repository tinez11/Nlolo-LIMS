import { z } from 'zod';
import type { OpenCaseRequest } from '@/api/types';
import { AMOUNT_PATTERN } from '@/lib/money';
import { CURRENCY_PATTERN, ISO_DATE_PATTERN, UUID_PATTERN } from '@/lib/patterns';
// Imported across features on purpose. A proposal's nominations and a policy's beneficiaries
// have to mean exactly the same thing -- the issuance listener MAPS one to the other rather
// than interpreting it -- and one schema is the only way to guarantee they cannot drift.
import { beneficiaryListSchema, type BeneficiaryFormValues } from '@/features/policies/beneficiaryForm';

/**
 * An optional whole number of months, kept as a string so an empty field stays empty.
 *
 * Deliberately a local copy of `policyIssueForm`'s identical helper rather than an import:
 * that one is private to its module, and five lines of validator is a smaller cost than
 * making the underwriting form depend on the policy issuance form's internals. The
 * beneficiary schema above is imported precisely because the opposite is true of it — there
 * the shared meaning IS the requirement.
 */
const months = (label: string) =>
  z
    .string()
    .trim()
    .refine((v) => v === '' || /^\d+$/.test(v), `${label} must be a whole number of months`)
    .refine((v) => v === '' || Number(v) >= 1, `${label} must be at least 1 month`);

/** Zod schema for `POST /underwriting/cases`, mirroring `OpenCaseRequest` exactly. */
export const openCaseFormSchema = z.object({
  applicantPartyId: z
    .string()
    .trim()
    .min(1, 'Applicant party id is required')
    .regex(UUID_PATTERN, 'Not a valid party id'),
  productId: z.string().trim().min(1, 'Select a product'),
  productVersionId: z.string().trim().min(1, 'Select a product'),
  sumAssuredAmount: z
    .string()
    .regex(AMOUNT_PATTERN, 'Must be a decimal amount like 1500000.00')
    .refine((v) => Number(v) >= 0.01, 'Must be at least 0.01'),
  sumAssuredCurrency: z.string().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS'),
  /**
   * Who sold it. Optional — a self-service application is a genuine direct sale — but it is
   * what makes commission accrue when the decision auto-issues a policy: distribution skips
   * accrual entirely for a policy with no agent of record, which is why no commission had
   * ever accrued on the automatic path.
   */
  agentOfRecordId: z
    .string()
    .trim()
    .refine((v) => v === '' || UUID_PATTERN.test(v), 'Not a valid agent id'),

  /**
   * Whose life is insured, when that is not the applicant.
   *
   * Blank means self-insured — the common case, and the only one the model could express
   * before this field existed. Most life business is not self-insured though: a parent
   * insures a child, an employer its staff. Both group business and credit life are
   * structurally impossible without the distinction.
   */
  lifeAssuredPartyId: z
    .string()
    .trim()
    .refine((v) => v === '' || UUID_PATTERN.test(v), 'Not a valid party id'),

  // IFRS 17 I2: controlled refdata codes, both optional -- blank takes the case's defaults (the agent's channel and
  // branch, else DIRECT and the opening staff member's branch). They replace the old free-text branch.
  branchCode: z.string().trim(),
  salesChannel: z.enum(['', 'AGENT', 'BROKER', 'BANCASSURANCE', 'DIRECT', 'DIGITAL']),

  /** Free text: the platform does not own this vocabulary until TIRA publishes one. */
  sourceOfBusiness: z.string().trim().max(60, 'Cannot exceed 60 characters'),

  proposedCommencementDate: z
    .string()
    .trim()
    .refine((v) => v === '' || ISO_DATE_PATTERN.test(v), 'Not a valid date'),

  /**
   * What the applicant asks for about the CONTRACT, as distinct from the risk above.
   *
   * These lived only on the manual issue form until now, which made it the only screen able
   * to produce a complete policy: one issued on the normal path had no term, no maturity date
   * (it is derived from commencement plus term) and nobody nominated, because nobody had ever
   * asked. That is very likely why staff reached for manual issue.
   *
   * Kept as strings and coerced on submit, the same as every other numeric in this console:
   * `z.coerce.number()` turns '' into 0, and a term of zero is a different statement from
   * "this product does not term".
   */
  requestedTermMonths: months('Term'),
  premiumPayingTermMonths: months('Premium-paying term'),

  /** '' means the applicant did not say, which the backend stores as null. */
  premiumFrequency: z.enum(['', 'MONTHLY', 'QUARTERLY', 'ANNUALLY', 'SINGLE']),

  /**
   * Nominations as taken on the proposal form.
   *
   * Reuses the policy side's own list schema rather than a second one. The two lists have to
   * mean exactly the same thing for the issuance listener's mapping to stay a mapping rather
   * than an interpretation, and one schema is the only way to guarantee that.
   */
  beneficiaries: beneficiaryListSchema,

  /**
   * An annuity purchase (product step 5). `isAnnuity` and `annuityJointRequired` are set by the page
   * from the product and the chosen form, never typed. The sum assured above is the purchase price.
   */
  isAnnuity: z.boolean(),
  annuityJointRequired: z.boolean(),
  annuityFormCode: z.string().trim(),
  annuityFrequency: z.string().trim(),
  annuityJointLifePartyId: z
    .string()
    .trim()
    .refine((v) => v === '' || UUID_PATTERN.test(v), 'Not a valid party id'),

  /**
   * A deferred annuity (product step 5 D2): set by the page from the version's vesting terms. Its
   * form is chosen when it vests, so the case records only the retirement age, and the sum assured
   * above is the contribution per payment.
   */
  isDeferredAnnuity: z.boolean(),
  retirementAge: z.string().trim(),

  /**
   * A funeral plan (family funeral cover): set by the page from the product. The applicant (or life
   * assured) is the main member; the plan picks the benefits, and the dependants are names on the case.
   * The sum assured above is filled from the plan's main-member benefit, never typed (plan R3).
   */
  isFuneral: z.boolean(),
  funeralPlanCode: z.string().trim(),
  funeralDependants: z.array(z.object({
    role: z.enum(['SPOUSE', 'CHILD', 'PARENT', 'EXTENDED']),
    fullName: z.string().trim(),
    dateOfBirth: z.string().trim(),
    sex: z.string().trim(),
    student: z.boolean(),
  })),

  /**
   * A unit-linked case (product step 6): set by the page from the product. The customer chooses the
   * premium and how it is split across the version's funds; the sum assured above is the cover they
   * chose, and the premium frequency below is how often they pay. Not rated: issuance uses both verbatim.
   */
  isUnitLinked: z.boolean(),
  ulPremium: z.string().trim(),
  ulSplit: z.array(z.object({ fundCode: z.string(), percent: z.string().trim() })),
}).superRefine((values, ctx) => {
  // Mirrors UnitLinkedChoices.validate, in its words. The minimum premium and the sum-assured range are
  // the version's, and are checked by the page against the terms it read, then by the server.
  if (values.isUnitLinked) {
    if (!/^\d+(\.\d{1,2})?$/.test(values.ulPremium) || !(Number(values.ulPremium) > 0)) {
      ctx.addIssue({ code: 'custom', path: ['ulPremium'], message: 'The premium the customer chose, as an amount above zero' });
    }
    if (values.premiumFrequency === '') {
      ctx.addIssue({ code: 'custom', path: ['premiumFrequency'], message: 'Choose how often the premium is paid' });
    }
    let total = 0;
    values.ulSplit.forEach((row, i) => {
      if (row.percent === '') return;
      const p = Number(row.percent);
      if (!Number.isInteger(p) || p < 1 || p > 100) {
        ctx.addIssue({ code: 'custom', path: ['ulSplit', i, 'percent'], message: "Each fund's share is a whole percent from 1 to 100" });
      } else {
        total += p;
      }
    });
    if (values.ulSplit.every((r) => r.percent === '')) {
      ctx.addIssue({ code: 'custom', path: ['ulSplit'], message: 'A unit-linked case says how each premium is split across the funds' });
    } else if (total !== 100) {
      ctx.addIssue({ code: 'custom', path: ['ulSplit'], message: `The fund split totals ${total}%; it must total 100%` });
    }
  }
  if (values.isFuneral) {
    if (values.funeralPlanCode === '') {
      ctx.addIssue({ code: 'custom', path: ['funeralPlanCode'], message: 'Choose the plan' });
    }
    if (values.premiumFrequency === '') {
      ctx.addIssue({ code: 'custom', path: ['premiumFrequency'], message: 'Choose how often the premium is paid' });
    }
    values.funeralDependants.forEach((d, i) => {
      if (d.fullName === '') ctx.addIssue({ code: 'custom', path: ['funeralDependants', i, 'fullName'], message: 'The full name is required' });
      if (!/^\d{4}-\d{2}-\d{2}$/.test(d.dateOfBirth)) {
        ctx.addIssue({ code: 'custom', path: ['funeralDependants', i, 'dateOfBirth'], message: 'The date of birth is required' });
      }
    });
  }
  if (values.isDeferredAnnuity) {
    if (values.retirementAge === '' || !Number.isInteger(Number(values.retirementAge))) {
      ctx.addIssue({ code: 'custom', path: ['retirementAge'], message: 'Enter the retirement age in whole years' });
    }
    if (values.premiumFrequency === '') {
      ctx.addIssue({ code: 'custom', path: ['premiumFrequency'], message: 'Choose how often contributions are paid' });
    }
  }
  // Mirrors UnderwritingApiImpl.recordAnnuityChoice, in its words where it has them.
  if (values.isAnnuity) {
    if (values.annuityFormCode === '') {
      ctx.addIssue({ code: 'custom', path: ['annuityFormCode'], message: 'Choose the annuity form' });
    }
    if (values.annuityFrequency === '') {
      ctx.addIssue({ code: 'custom', path: ['annuityFrequency'], message: 'Choose how often the income is paid' });
    }
    if (values.annuityJointRequired && values.annuityJointLifePartyId === '') {
      ctx.addIssue({
        code: 'custom',
        path: ['annuityJointLifePartyId'],
        message: `Form ${values.annuityFormCode} is joint-life: name the joint life`,
      });
    }
  }
  // Mirrors chk_proposal_paying_term_within_term, and policy's own
  // policy_premium_paying_term_within_term behind it: premiums may be paid for a shorter time
  // than cover runs (a limited-payment policy), never for longer.
  const term = values.requestedTermMonths;
  const payingTerm = values.premiumPayingTermMonths;
  if (term !== '' && payingTerm !== '' && Number(payingTerm) > Number(term)) {
    ctx.addIssue({
      code: 'custom',
      path: ['premiumPayingTermMonths'],
      message: 'Premiums cannot be paid for longer than cover runs',
    });
  }
  // Mirrors the SINGLE arm of Policy.applyTerm, as the manual issue form does. Left to issuance
  // it refused only after the decision, leaving a decided case with no policy (PRO-9A26219C,
  // 2026-10-09: SINGLE over 3 months with a paying term of 3).
  if (values.premiumFrequency === 'SINGLE' && payingTerm !== '' && Number(payingTerm) !== 1) {
    ctx.addIssue({
      code: 'custom',
      path: ['premiumPayingTermMonths'],
      message: 'A single premium is charged once, so this must be 1 month (or left blank)',
    });
  }
});

/**
 * Input/Output split, same reason as the policy issue form's: this schema now embeds
 * `beneficiaryListSchema`, which coerces `sharePercent` with `z.coerce.number()`. `useForm`
 * must be instantiated with both so react-hook-form routes the coerced Output type to
 * onSubmit while the raw Input type governs what register()/watch() see.
 */
export type OpenCaseFormValues = z.output<typeof openCaseFormSchema>;
export type OpenCaseFormInput = z.input<typeof openCaseFormSchema>;

export function blankOpenCaseForm(): OpenCaseFormInput {
  return {
    applicantPartyId: '',
    productId: '',
    productVersionId: '',
    sumAssuredAmount: '',
    sumAssuredCurrency: 'TZS',
    agentOfRecordId: '',
    lifeAssuredPartyId: '',
    branchCode: '',
    salesChannel: '',
    sourceOfBusiness: '',
    proposedCommencementDate: '',
    requestedTermMonths: '',
    premiumPayingTermMonths: '',
    // Blank, not MONTHLY. A default here would have the form assert a payment frequency the
    // applicant never stated, and it is the value the issued policy is billed on.
    premiumFrequency: '',
    beneficiaries: [],
    isAnnuity: false,
    annuityJointRequired: false,
    annuityFormCode: '',
    annuityFrequency: '',
    annuityJointLifePartyId: '',
    isDeferredAnnuity: false,
    retirementAge: '',
    isFuneral: false,
    funeralPlanCode: '',
    funeralDependants: [],
    isUnitLinked: false,
    ulPremium: '',
    ulSplit: [],
  };
}

export function blankFuneralDependant(): OpenCaseFormInput['funeralDependants'][number] {
  return { role: 'CHILD', fullName: '', dateOfBirth: '', sex: '', student: false };
}

/** The dependants as the funeral application and the live quote carry them. */
export function toFuneralDependants(values: Pick<OpenCaseFormInput, 'funeralDependants'>) {
  return values.funeralDependants.map((d) => ({
    role: d.role,
    fullName: d.fullName.trim(),
    dateOfBirth: d.dateOfBirth,
    sex: d.sex === '' ? null : (d.sex as 'FEMALE' | 'MALE'),
    student: d.role === 'CHILD' && d.student,
  }));
}

export function toApiRequest(values: OpenCaseFormValues): OpenCaseRequest {
  // A unit-linked case: the fund split, premium and sum assured travel with the case, recorded in the
  // same request. The term is optional -- whole of life when blank, a maturity date when given.
  if (values.isUnitLinked) {
    return {
      ...baseRequest(values),
      ...(values.requestedTermMonths ? { requestedTermMonths: Number(values.requestedTermMonths) } : {}),
      ...(values.premiumFrequency ? { premiumFrequency: values.premiumFrequency } : {}),
      unitLinked: {
        split: values.ulSplit.filter((r) => r.percent !== '').map((r) => ({ fundCode: r.fundCode, percent: Number(r.percent) })),
        premium: Number(values.ulPremium),
        frequency: values.premiumFrequency as NonNullable<OpenCaseRequest['unitLinked']>['frequency'],
        sumAssured: Number(values.sumAssuredAmount),
      },
    };
  }
  // A funeral plan: the family travels with the case, recorded in the same request. No term -- it
  // renews yearly -- and the frequency it is billed on.
  if (values.isFuneral) {
    return {
      ...baseRequest(values),
      ...(values.premiumFrequency ? { premiumFrequency: values.premiumFrequency } : {}),
      funeral: { planCode: values.funeralPlanCode, dependants: toFuneralDependants(values) },
    };
  }
  // A deferred annuity (D2) pays contributions to its vesting date and has no term: the issue
  // listener derives the paying term from the retirement age.
  if (values.isDeferredAnnuity) {
    return {
      ...baseRequest(values),
      ...(values.premiumFrequency ? { premiumFrequency: values.premiumFrequency } : {}),
      deferredAnnuity: { retirementAge: Number(values.retirementAge) },
    };
  }
  // An annuity has no term and no premium frequency: it is bought with one single premium, the
  // purchase price, and pays until death. The choice travels instead, and only then.
  if (values.isAnnuity) {
    return {
      ...baseRequest(values),
      annuityChoice: {
        formCode: values.annuityFormCode,
        frequency: values.annuityFrequency as NonNullable<NonNullable<OpenCaseRequest['annuityChoice']>['frequency']>,
        jointLifePartyId: values.annuityJointRequired ? values.annuityJointLifePartyId : null,
      },
    };
  }
  return {
    ...baseRequest(values),
    // Same omit-when-blank rule as everything above. A blank term is not zero months and not
    // a null to send: it means this product does not term, or the applicant did not say.
    ...(values.requestedTermMonths ? { requestedTermMonths: Number(values.requestedTermMonths) } : {}),
    ...(values.premiumPayingTermMonths
      ? { premiumPayingTermMonths: Number(values.premiumPayingTermMonths) }
      : {}),
    ...(values.premiumFrequency ? { premiumFrequency: values.premiumFrequency } : {}),
  };
}

function baseRequest(values: OpenCaseFormValues): OpenCaseRequest {
  return {
    applicantPartyId: values.applicantPartyId.trim(),
    productId: values.productId,
    productVersionId: values.productVersionId,
    sumAssured: { amount: values.sumAssuredAmount, currencyCode: values.sumAssuredCurrency },
    // Omitted entirely when blank rather than sent as '': the field is a nullable uuid, and
    // an empty string is neither a uuid nor an absence the backend would accept.
    ...(values.agentOfRecordId ? { agentOfRecordId: values.agentOfRecordId } : {}),
    // Same omit-when-blank rule. A blank life assured is not a null to send: it means
    // self-insured, and the backend resolves that to the applicant rather than storing a
    // null every later reader would have to interpret.
    ...(values.lifeAssuredPartyId ? { lifeAssuredPartyId: values.lifeAssuredPartyId } : {}),
    ...(values.branchCode ? { branchCode: values.branchCode } : {}),
    ...(values.salesChannel ? { salesChannel: values.salesChannel } : {}),
    ...(values.sourceOfBusiness ? { sourceOfBusiness: values.sourceOfBusiness } : {}),
    ...(values.proposedCommencementDate
      ? { proposedCommencementDate: values.proposedCommencementDate }
      : {}),
    // Omitted entirely when nobody was nominated, rather than sent as []. Both mean the same
    // thing to the backend, but an absent key says "not stated on this proposal" where an
    // empty array reads as "stated, and it is nobody".
    ...(values.beneficiaries.length > 0
      ? { beneficiaries: toApiNominations({ beneficiaries: values.beneficiaries }) }
      : {}),
  };
}

/**
 * The proposal's nominations in the shape `POST /underwriting/cases` expects.
 *
 * Identical in output to the policy side's `toApiBeneficiaries` — the two wire schemas are
 * field-for-field the same, which is what lets the issuance listener map rather than
 * interpret. Written out here rather than reused because the two return types are generated
 * from different specs and only coincide structurally; sharing the function would tie the
 * underwriting request shape to the policy one at the type level, which is precisely the
 * dependency the backend modules refuse.
 */
function toApiNominations(values: BeneficiaryFormValues): NonNullable<OpenCaseRequest['beneficiaries']> {
  return values.beneficiaries.map((row) =>
    row.type === 'PARTY'
      ? {
          type: 'PARTY' as const,
          partyId: row.partyId.trim(),
          sharePercent: row.sharePercent,
          revocable: row.revocable,
        }
      : {
          type: 'FREEFORM' as const,
          freeformDesignee: row.freeformDesignee.trim(),
          sharePercent: row.sharePercent,
          revocable: row.revocable,
        },
  );
}
