import { z } from 'zod';
import type { CreditLifeAgreement, CreditLifeAgreementInput } from '@/api/types';
import { todayIso } from '@/lib/dates';
import { AMOUNT_PATTERN } from '@/lib/money';
import { CREDIT_LIFE_PREMIUM_BASES } from './creditLifeSchemeIssueForm';

/**
 * A year's agreement with a credit-life scheme's lender (policy V43, 2026-10-30).
 *
 * The bank lends on its own terms; what it agrees with the insurer, once a year, is the rate and how it is applied,
 * the least a loan pays, the oldest borrower, the shortest and longest loan, its commission and whether cover includes
 * the loan's interest. Every rule here is also the server's; this form only says so before the round trip.
 */

/** TIRA's cap on a lender's credit-life commission. The server refuses above it too. */
export const COMMISSION_CAP_PERCENT = 15;

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const PERCENT = /^\d+(\.\d{1,4})?$/;
const WHOLE = /^\d+$/;

export interface AgreementFormValues {
  validFrom: string;
  validTo: string;
  premiumRatePercent: string;
  premiumBasis: (typeof CREDIT_LIFE_PREMIUM_BASES)[number];
  minimumPremium: string;
  maxAge: string;
  minTermMonths: string;
  maxTermMonths: string;
  commissionPercent: string;
  coverIncludesInterest: boolean;
}

/** A year from the day after the last agreement ends, or from today when there is none. */
export function defaultAgreementValues(previous: CreditLifeAgreement | null, today: string = todayIso()):
  AgreementFormValues {
  const from = previous ? dayAfter(previous.validTo) : today;
  return {
    validFrom: from,
    validTo: yearEnding(from),
    // A renewal usually keeps last year's terms; the person changes what was renegotiated.
    premiumRatePercent: previous?.premiumRatePercent ?? '',
    premiumBasis: previous?.premiumBasis ?? 'WHOLE_YEARS_ROUNDED_UP',
    minimumPremium: previous?.minimumPremium ?? '',
    maxAge: previous ? String(previous.maxAge) : '',
    // Every loan starts at a year (the user, 2026-10-30).
    minTermMonths: previous ? String(previous.minTermMonths) : '12',
    maxTermMonths: previous ? String(previous.maxTermMonths) : '',
    commissionPercent: previous?.commissionPercent ?? '',
    coverIncludesInterest: previous?.coverIncludesInterest ?? false,
  };
}

export function agreementFormSchema() {
  return z
    .object({
      validFrom: z.string().trim().regex(ISO_DATE, 'Choose the first day of the agreement'),
      validTo: z.string().trim().regex(ISO_DATE, 'Choose the last day of the agreement'),
      premiumRatePercent: z
        .string()
        .trim()
        .regex(PERCENT, 'A percent like 0.6')
        .refine((v) => Number(v) > 0, 'The rate must be more than 0'),
      premiumBasis: z.enum(CREDIT_LIFE_PREMIUM_BASES),
      minimumPremium: z
        .string()
        .trim()
        .refine((v) => v === '' || AMOUNT_PATTERN.test(v), 'An amount like 2000, or blank for none'),
      maxAge: z
        .string()
        .trim()
        .regex(WHOLE, 'A whole number of years')
        .refine((v) => Number(v) >= 18 && Number(v) <= 100, 'Between 18 and 100'),
      minTermMonths: z.string().trim().regex(WHOLE, 'A whole number of months')
        .refine((v) => Number(v) >= 1, 'At least 1 month'),
      maxTermMonths: z.string().trim().regex(WHOLE, 'A whole number of months')
        .refine((v) => Number(v) <= 480, 'At most 480 months'),
      commissionPercent: z
        .string()
        .trim()
        .refine((v) => v === '' || PERCENT.test(v), 'A percent like 10, or blank for none')
        .refine((v) => v === '' || Number(v) <= COMMISSION_CAP_PERCENT,
          `At most ${COMMISSION_CAP_PERCENT}% (TIRA)`),
      coverIncludesInterest: z.boolean(),
    })
    .superRefine((v, ctx) => {
      if (ISO_DATE.test(v.validFrom) && ISO_DATE.test(v.validTo) && v.validTo < v.validFrom) {
        ctx.addIssue({ code: 'custom', path: ['validTo'], message: 'Ends before it starts' });
      }
      if (WHOLE.test(v.minTermMonths) && WHOLE.test(v.maxTermMonths)
          && Number(v.maxTermMonths) < Number(v.minTermMonths)) {
        ctx.addIssue({ code: 'custom', path: ['maxTermMonths'], message: 'Shorter than the shortest loan' });
      }
    });
}

/** Decimals stay strings, as all money on this platform travels. Blank optional amounts are omitted. */
export function toAgreementInput(v: AgreementFormValues): CreditLifeAgreementInput {
  return {
    validFrom: v.validFrom,
    validTo: v.validTo,
    premiumRatePercent: v.premiumRatePercent.trim(),
    premiumBasis: v.premiumBasis,
    ...(v.minimumPremium.trim() ? { minimumPremium: v.minimumPremium.trim() } : {}),
    maxAge: Number(v.maxAge),
    minTermMonths: Number(v.minTermMonths),
    maxTermMonths: Number(v.maxTermMonths),
    ...(v.commissionPercent.trim() ? { commissionPercent: v.commissionPercent.trim() } : {}),
    coverIncludesInterest: v.coverIncludesInterest,
  };
}

/** The agreement covering {@code day}, or null. Agreements never overlap, so there is at most one. */
export function agreementOn(agreements: CreditLifeAgreement[], day: string = todayIso()):
  CreditLifeAgreement | null {
  return agreements.find((a) => a.validFrom <= day && day <= a.validTo) ?? null;
}

/** How the rate becomes a premium, in the words the lender's template uses. A restatement, never a figure. */
export function pricingRuleInWords(a: Pick<CreditLifeAgreement, 'premiumRatePercent' | 'premiumBasis'>): string {
  const rate = `${trimZeros(a.premiumRatePercent)}%`;
  switch (a.premiumBasis) {
    case 'WHOLE_YEARS_ROUNDED_UP':
      return `Loan amount × ${rate} for each year the loan runs; part of a year counts as a full year`;
    case 'PER_ANNUM_ON_PRINCIPAL':
      return `Loan amount × ${rate} a year, for the loan's months`;
    case 'FLAT_ON_PRINCIPAL':
      return `Loan amount × ${rate}, once, whatever the term`;
    case 'ANNUAL_ON_DECLINING_BALANCE':
      return `${rate} a year on what is still owed at the start of each year`;
  }
}

/** Whether a server decimal is above zero, read from its digits rather than through a double. */
export function isPositive(decimal: string): boolean {
  return !decimal.startsWith('-') && /[1-9]/.test(decimal);
}

/** "0.6000" reads as 0.6, "10.00" as 10: the server's scale is not the agreed figure. */
export function trimZeros(decimal: string): string {
  return decimal.includes('.') ? decimal.replace(/0+$/, '').replace(/\.$/, '') : decimal;
}

function dayAfter(iso: string): string {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

/** The day before the same date next year: a year from 1 Jan 2027 ends on 31 Dec 2027. */
function yearEnding(fromIso: string): string {
  const d = new Date(`${fromIso}T00:00:00Z`);
  d.setUTCFullYear(d.getUTCFullYear() + 1);
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
}
