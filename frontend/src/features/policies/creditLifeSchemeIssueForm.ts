import { z } from 'zod';
import type { IssueGroupSchemeRequest } from '@/api/types';
import { todayIso } from '@/lib/dates';
import { AMOUNT_PATTERN } from '@/lib/money';
import { CURRENCY_PATTERN, ISO_DATE_PATTERN, UUID_PATTERN } from '@/lib/patterns';

/**
 * Setting up a lender's credit-life scheme.
 *
 * ## Why this is not the group-scheme form with a fourth basis
 *
 * `groupSchemeIssueForm` collects a benefit basis and a schedule of *employees*, and hands the
 * result to `POST /underwriting/group-cases` — a scheme is proposed, an underwriter decides it,
 * and the decision issues it. None of that fits here, and not for stylistic reasons:
 *
 *  - the underwriting module has its OWN benefit-basis enum, `GroupBenefitBasis`, with three
 *    values and no `AMORTISING_LOAN`, and its `MemberLineDto` carries a party id, a grade and a
 *    salary — there is nowhere in a group case for a loan to go. Routing credit life through it
 *    means widening underwriting's enum, its member line, its proposal record and the
 *    decision-to-issuance mapping, and first deciding what underwriting a *book* even means;
 *  - a credit-life borrower is not underwritten as a case anyway. The scheme's free cover limit
 *    is the underwriting decision, agreed once with the lender, and every borrower after that is
 *    judged row by row against it by the enrolment pipeline.
 *
 * So this form uses `POST /group-schemes`, which already accepts every credit-life term. That
 * endpoint had no caller in this console at all, which is why no credit-life scheme could be set
 * up here: every one in existence was made with curl.
 *
 * ## Why the issuance basis is asked for
 *
 * Left null, a scheme is issued as an OFFER — PROPOSED until the first premium clears — and this
 * console has no action that accepts an offer, for group or individual business. So a scheme
 * created that way cannot receive a single enrolment file until money arrives by some other
 * route. That is a real wall and the form says so beside the field rather than letting somebody
 * discover it after they have typed in a lender.
 *
 * A lender's existing book is a MIGRATION, which is both true and the basis that puts the scheme
 * on risk immediately.
 */

export const INTEREST_METHODS = ['FLAT_RATE', 'REDUCING_BALANCE'] as const;
export const LOAN_REPAYMENT_FREQUENCIES = ['MONTHLY', 'QUARTERLY'] as const;

/**
 * The bases that start cover at once — the only ones that leave a usable scheme today.
 *
 * The other two (`UNDERWRITING_OVERRIDE`, `GUARANTEED_ISSUE`) are real and are deliberately not
 * offered: both leave the scheme an offer, so picking one would produce exactly the dead end
 * above while looking like a choice.
 */
export const IN_FORCE_ISSUANCE_BASES = ['MIGRATION', 'CONVERSION', 'REINSTATEMENT'] as const;

export interface CreditLifeSchemeIssueFormValues {
  policyholderPartyId: string;
  productId: string;
  productVersionId: string;
  currency: string;
  fclAmount: string;
  interestMethod: (typeof INTEREST_METHODS)[number];
  repaymentFrequency: (typeof LOAN_REPAYMENT_FREQUENCIES)[number];
  premiumRatePercent: string;
  premiumAmount: string;
  premiumCurrency: string;
  commencementDate: string;
  issuanceBasis: (typeof IN_FORCE_ISSUANCE_BASES)[number] | '';
  reasonForManualIssue: string;
}

export function blankCreditLifeSchemeIssueForm(): CreditLifeSchemeIssueFormValues {
  return {
    policyholderPartyId: '',
    productId: '',
    productVersionId: '',
    currency: 'TZS',
    fclAmount: '',
    interestMethod: 'FLAT_RATE',
    repaymentFrequency: 'MONTHLY',
    premiumRatePercent: '',
    premiumAmount: '',
    premiumCurrency: 'TZS',
    commencementDate: '',
    issuanceBasis: 'MIGRATION',
    reasonForManualIssue: '',
  };
}

export function creditLifeSchemeIssueFormSchema(today: string = todayIso()) {
  return z
    .object({
      policyholderPartyId: z
        .string()
        .trim()
        .min(1, 'Choose the lender that holds the contract')
        .regex(UUID_PATTERN, 'Not a valid party'),
      productId: z.string().trim().min(1, 'Select a credit-life product'),
      productVersionId: z.string().trim().min(1, 'Select a credit-life product'),
      currency: z.string().trim().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS'),
      // Blank means the scheme has NO free cover limit -- a real design, and not a limit of
      // zero, which would send every borrower to underwriting.
      fclAmount: z
        .string()
        .trim()
        .refine(
          (v) => v === '' || AMOUNT_PATTERN.test(v),
          'Free cover limit must be an amount like 600000000.00',
        )
        .refine((v) => v === '' || Number(v) >= 0.01, 'Free cover limit must be at least 0.01'),
      interestMethod: z.enum(INTEREST_METHODS),
      repaymentFrequency: z.enum(LOAN_REPAYMENT_FREQUENCIES),
      premiumRatePercent: z
        .string()
        .trim()
        .min(1, 'The rate the lender is charged, as a percent of each loan')
        .refine((v) => /^\d+(\.\d{1,4})?$/.test(v), 'Must be a percent like 0.5 or 0.5000')
        .refine((v) => Number(v) >= 0.0001, 'Must be at least 0.0001'),
      premiumAmount: z
        .string()
        .trim()
        .regex(AMOUNT_PATTERN, 'Must be an amount like 52000.00')
        .refine((v) => Number(v) >= 0.01, 'Must be at least 0.01'),
      premiumCurrency: z.string().trim().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS'),
      /*
       * REQUIRED here, unlike on the employer-scheme form where it may be omitted.
       *
       * Omitting it means the scheme commences today, and a credit-life scheme is almost always
       * a book that already exists: its opening borrower was lent to weeks or months ago. The
       * service then refuses the whole submission with "Member X cannot join on <date>, before
       * the scheme commenced on <today>" -- correct, and a sentence about a field the form never
       * asked for. Asking is the fix; the cross-check below is the other half.
       */
      commencementDate: z
        .string()
        .trim()
        .min(1, 'When this scheme starts — on or before the oldest loan the lender will send')
        .refine((v) => ISO_DATE_PATTERN.test(v), 'Not a valid date'),
      issuanceBasis: z.union([z.enum(IN_FORCE_ISSUANCE_BASES), z.literal('')]),
      reasonForManualIssue: z.string().trim(),
    })
    .superRefine((values, ctx) => {
      const issue = (path: (string | number)[], message: string) =>
        ctx.addIssue({ code: 'custom', path, message });

      // Backdating a scheme is normal; forward-dating is refused, because its total would not
      // match its members until then.
      if (values.commencementDate !== '' && values.commencementDate > today) {
        issue(['commencementDate'], 'A scheme cannot commence in the future yet');
      }
    });
}

/**
 * Blank optional fields are OMITTED, never sent as empty strings.
 *
 * The borrower is FREEFORM rather than a party id, and that is the product rather than a
 * shortcut: a lender sends several hundred names on a spreadsheet, none of whom the insurer has
 * a party record for, and minting one per borrower would put a KYC obligation on a life whose
 * cover the lender owns.
 */
export function toIssueRequest(values: CreditLifeSchemeIssueFormValues): IssueGroupSchemeRequest {
  return {
    policyholderPartyId: values.policyholderPartyId.trim(),
    productVersionId: values.productVersionId.trim(),
    // Present-but-nullable, matching manual issue: a scheme sold direct has no broker, which is
    // not the same as a missing field.
    agentOfRecordId: null,
    benefitBasis: 'AMORTISING_LOAN',
    ...(values.fclAmount ? { fclAmount: values.fclAmount.trim() } : {}),
    currency: values.currency.trim().toUpperCase(),
    interestMethod: values.interestMethod,
    repaymentFrequency: values.repaymentFrequency,
    premiumRatePercent: Number(values.premiumRatePercent),
    /*
     * NO OPENING SCHEDULE, and the empty array is deliberate rather than an omission.
     *
     * A credit-life scheme is an agreement with a lender -- the rate, the free cover limit, the
     * interest method -- and it exists before any borrower does. The book arrives monthly by file
     * and never stops arriving, so requiring one borrower at set-up made somebody type a life
     * they then met again on the member roll without recognising them.
     *
     * The service permits an empty schedule on AMORTISING_LOAN and only there; an employer
     * scheme's schedule IS its contract and still cannot be empty.
     */
    openingSchedule: [],
    premium: {
      amount: values.premiumAmount.trim(),
      currencyCode: values.premiumCurrency.trim().toUpperCase(),
    },
    premiumFrequency: 'SINGLE',
    ...(values.commencementDate ? { commencementDate: values.commencementDate } : {}),
    ...(values.issuanceBasis ? { issuanceBasis: values.issuanceBasis } : {}),
    ...(values.reasonForManualIssue.trim()
      ? { reasonForManualIssue: values.reasonForManualIssue.trim() }
      : {}),
  };
}
