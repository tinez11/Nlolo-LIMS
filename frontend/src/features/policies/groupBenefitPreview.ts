import type { BenefitBasis, Money } from '@/api/types';
import { AMOUNT_PATTERN } from '@/lib/money';

/**
 * What a member will be worth and what they will actually be covered for, shown
 * before the row is submitted.
 *
 * A mirror of `policy.domain.GroupBenefitCalculator`, and deliberately so: the
 * free cover limit is the one thing a scheme administrator needs to know while
 * typing a salary, not after saving. Discovering that a new hire needs medical
 * evidence on the next screen is discovering it too late to ask them about it
 * while they are still standing there.
 *
 * **The server remains the authority.** Nothing here is sent; the request carries
 * the salary or grade only, and the backend recomputes and stores the result. This
 * exists to make the consequence visible, the same bargain `maturityPreview` and
 * `src/gates` already make. The counterpart tests are
 * `GroupBenefitCalculatorTest` (Java) and this module's own (TypeScript); they
 * assert the same worked examples so drift shows up as a red test rather than as a
 * number on screen that the server disagrees with.
 *
 * ## Why the arithmetic looks like this
 *
 * `lib/money.ts` has no arithmetic in it on purpose: money is a decimal string,
 * and a JS double silently mangles decimals the backend stores exactly. A
 * salary-multiple benefit is the one place the console genuinely has to multiply,
 * so it is done in scaled integers via `BigInt` -- exact at any magnitude -- and
 * rounded HALF_UP to two places, which is what `BigDecimal.setScale(2, HALF_UP)`
 * does on the other side. `20000000.00 * 3` through a float would be right; it is
 * the odd salary against a 3.33 multiple that would not be, and that is the case
 * nobody would notice.
 */

/** A decimal parsed into an exact integer and the power of ten it is scaled by. */
interface Scaled {
  digits: bigint;
  scale: number;
}

function parseScaled(decimal: string): Scaled | null {
  const trimmed = decimal.trim();
  if (!/^\d+(\.\d+)?$/.test(trimmed)) return null;
  const [whole, fraction = ''] = trimmed.split('.');
  return { digits: BigInt(whole + fraction), scale: fraction.length };
}

/** Render a scaled integer as a 2dp decimal string, rounding HALF_UP. */
function toAmountString({ digits, scale }: Scaled): string {
  let value = digits;
  if (scale > 2) {
    const divisor = 10n ** BigInt(scale - 2);
    const remainder = value % divisor;
    value = value / divisor;
    // HALF_UP: exactly half rounds away from zero. Everything here is
    // non-negative, so "away from zero" is "up".
    if (remainder * 2n >= divisor) value += 1n;
  } else if (scale < 2) {
    value *= 10n ** BigInt(2 - scale);
  }
  const asText = value.toString().padStart(3, '0');
  return `${asText.slice(0, -2)}.${asText.slice(-2)}`;
}

/** Compare two 2dp decimal strings exactly. Negative, zero or positive, like Java's compareTo. */
export function compareAmounts(left: string, right: string): number {
  const a = parseScaled(left);
  const b = parseScaled(right);
  if (a === null || b === null) return Number.NaN;
  const scale = Math.max(a.scale, b.scale);
  const lifted = (v: Scaled) => v.digits * 10n ** BigInt(scale - v.scale);
  const x = lifted(a);
  const y = lifted(b);
  return x === y ? 0 : x < y ? -1 : 1;
}

export type PreviewUnderwritingStatus = 'WITHIN_FCL' | 'EVIDENCE_REQUIRED';

export interface BenefitPreview {
  /** What the basis says this member is worth. */
  benefit: Money;
  /** What they will actually be insured for — the benefit, capped at the limit. */
  covered: Money;
  status: PreviewUnderwritingStatus;
  /** The part of the benefit that only medical evidence will unlock. */
  excess: Money | null;
}

export interface SchemeBasis {
  benefitBasis: BenefitBasis;
  currency: string;
  /** 2dp decimal string. Required on a FLAT scheme. */
  flatBenefitAmount?: string | null;
  /** A ratio, not money. Required on a SALARY_MULTIPLE scheme. */
  salaryMultiple?: number | null;
  /** 2dp decimal string, or null for a scheme with no limit — NOT a limit of zero. */
  fclAmount?: string | null;
  /** grade code → 2dp decimal benefit. Required on a GRADED scheme. */
  gradeBenefits?: Record<string, string>;
}

export interface MemberBasisInput {
  /** 2dp decimal string, on a SALARY_MULTIPLE scheme. */
  salaryAmount?: string;
  /** On a GRADED scheme. */
  gradeCode?: string;
}

/**
 * The benefit this member's inputs produce, or null when the inputs are not yet
 * enough to say.
 *
 * Null rather than a zero or a guess: a partially typed salary must show nothing,
 * not "covered for TZS 0.00", which reads as a real and alarming answer.
 */
export function previewBenefit(
  scheme: SchemeBasis,
  member: MemberBasisInput,
): BenefitPreview | null {
  const benefitAmount = benefitFor(scheme, member);
  if (benefitAmount === null) return null;

  const money = (amount: string): Money => ({ amount, currencyCode: scheme.currency });
  const fcl = scheme.fclAmount;

  // A null limit means the scheme has NO limit and everybody is covered in full.
  // It is not a limit of zero, which would send every member to underwriting.
  if (fcl === null || fcl === undefined || fcl === '') {
    return {
      benefit: money(benefitAmount),
      covered: money(benefitAmount),
      status: 'WITHIN_FCL',
      excess: null,
    };
  }

  if (compareAmounts(benefitAmount, fcl) <= 0) {
    return {
      benefit: money(benefitAmount),
      covered: money(benefitAmount),
      status: 'WITHIN_FCL',
      excess: null,
    };
  }

  // Over the limit: covered up to it immediately, the excess only on acceptance.
  return {
    benefit: money(benefitAmount),
    covered: money(fcl),
    status: 'EVIDENCE_REQUIRED',
    excess: money(subtract(benefitAmount, fcl)),
  };
}

function benefitFor(scheme: SchemeBasis, member: MemberBasisInput): string | null {
  switch (scheme.benefitBasis) {
    case 'FLAT':
      return normalise(scheme.flatBenefitAmount);
    case 'GRADED': {
      const code = member.gradeCode?.trim();
      if (!code) return null;
      return normalise(scheme.gradeBenefits?.[code]);
    }
    case 'SALARY_MULTIPLE': {
      const salary = member.salaryAmount?.trim();
      if (!salary || !AMOUNT_PATTERN.test(salary)) return null;
      const multiple = scheme.salaryMultiple;
      if (multiple === null || multiple === undefined || !(multiple > 0)) return null;
      const salaryScaled = parseScaled(salary);
      // A multiple is a ratio and arrives as a JSON number; going through its own
      // decimal text keeps the multiplication exact rather than inheriting the
      // double's error.
      const multipleScaled = parseScaled(String(multiple));
      if (salaryScaled === null || multipleScaled === null) return null;
      return toAmountString({
        digits: salaryScaled.digits * multipleScaled.digits,
        scale: salaryScaled.scale + multipleScaled.scale,
      });
    }
  }
}

function normalise(amount: string | null | undefined): string | null {
  if (!amount) return null;
  const parsed = parseScaled(amount);
  return parsed === null ? null : toAmountString(parsed);
}

/** `left - right`, both non-negative 2dp decimals, with `left >= right`. */
function subtract(left: string, right: string): string {
  const a = parseScaled(left);
  const b = parseScaled(right);
  if (a === null || b === null) return '0.00';
  const scale = Math.max(a.scale, b.scale);
  const lift = (v: Scaled) => v.digits * 10n ** BigInt(scale - v.scale);
  return toAmountString({ digits: lift(a) - lift(b), scale });
}
