import { useEffect, useState } from 'react';
import { quotePremium } from '@/api/products';
import type { PremiumQuoteRequest, PremiumQuoteView } from '@/api/types';

/** What a quotation needs before it can be asked for at all. */
export interface QuotationInputs {
  productId: string;
  sumAssuredAmount: string;
  sumAssuredCurrency: string;
  frequency: PremiumQuoteRequest['frequency'] | '';
  policyTermMonths: string;
  /** From the applicant's own person record -- the three fields base_rate_table is keyed on. */
  dateOfBirth: string | null | undefined;
  sex: PremiumQuoteRequest['sex'] | null | undefined;
  smokerStatus: PremiumQuoteRequest['smokerStatus'] | null | undefined;
  occupationClass: string | null | undefined;
}

export type Quotation =
  | { state: 'incomplete'; missing: string[] }
  | { state: 'loading' }
  | { state: 'quoted'; quote: PremiumQuoteView; assumedOccupationClass: boolean }
  | { state: 'refused'; error: unknown };

/** A term the server can take: a whole number of months above zero ("12", not "12.5", "1 yr" or "-3"). */
export function isWholeMonths(value: string): boolean {
  return /^\d+$/.test(value.trim()) && Number(value) > 0;
}

/** An amount the server can take: digits, optionally with decimals -- no commas, spaces or currency. */
export function isAmount(value: string): boolean {
  return /^\d+(\.\d{1,2})?$/.test(value.trim()) && Number(value) > 0;
}

/** The occupation class assumed when the person record names none. Named on screen, never silent. */
export const DEFAULT_OCCUPATION_CLASS = 'STANDARD';

/**
 * What this life would be charged, read from the server on every change (2026-10-16).
 *
 * <p><b>It creates nothing.</b> `POST /products/{id}/premium-quote` is a read whose inputs happen to be a body; it
 * records no quotation, opens no case and posts nothing. Calling it as a form changes is safe by design, which is
 * what lets a preview sit under a form that has not been submitted.
 *
 * <p><b>The console never derives a premium.</b> Every figure below is the server's, including the annual
 * equivalent -- the quote returns it, so nothing here multiplies an instalment by twelve.
 *
 * <p>A 422 is an ORDINARY state, not a broken screen: a version with no base-rate cell for this age, sex and
 * smoker status cannot price this life, and saying so is the honest answer.
 */
/**
 * The premium-quote request for these inputs -- the one the hook sends, exported so an alternative (more cover, less
 * cover) is asked in exactly the same words with only the amount changed (2026-10-23).
 */
export function premiumQuoteRequest(inputs: QuotationInputs, sumAssuredAmount = inputs.sumAssuredAmount): PremiumQuoteRequest {
  return {
    sumAssuredAmount: Number(sumAssuredAmount),
    sumAssuredCurrency: inputs.sumAssuredCurrency || 'TZS',
    dateOfBirth: inputs.dateOfBirth as string,
    sex: inputs.sex as PremiumQuoteRequest['sex'],
    smokerStatus: inputs.smokerStatus ?? 'UNKNOWN',
    occupationClass: inputs.occupationClass || DEFAULT_OCCUPATION_CLASS,
    frequency: inputs.frequency as PremiumQuoteRequest['frequency'],
    ...(inputs.policyTermMonths ? { policyTermMonths: Number(inputs.policyTermMonths) } : {}),
  };
}

export function useQuotation(inputs: QuotationInputs): Quotation {
  const {
    productId,
    sumAssuredAmount,
    sumAssuredCurrency,
    frequency,
    policyTermMonths,
    dateOfBirth,
    sex,
    smokerStatus,
    occupationClass,
  } = inputs;

  // What is still missing, in the words of the fields a person would go and fill in.
  const missing: string[] = [];
  if (!productId) missing.push('a product');
  // `!(n > 0)`, not `n <= 0`: "1,000,000" is NaN, and NaN <= 0 is false -- it was sent as a null sum assured and
  // came back as a refusal blaming the rate table.
  if (!sumAssuredAmount || !(Number(sumAssuredAmount) > 0)) missing.push('a sum assured in digits');
  if (policyTermMonths && !isWholeMonths(policyTermMonths)) missing.push('a term in whole months');
  if (!frequency) missing.push('a payment frequency');
  if (!dateOfBirth) missing.push("the applicant's date of birth");
  if (!sex) missing.push("the applicant's sex");

  const ready = missing.length === 0;
  const key = ready
    ? [productId, sumAssuredAmount, sumAssuredCurrency, frequency, policyTermMonths, dateOfBirth, sex,
        smokerStatus ?? '', occupationClass ?? ''].join('|')
    : '';

  const [loaded, setLoaded] = useState<{ key: string; quote?: PremiumQuoteView; error?: unknown } | null>(null);

  useEffect(() => {
    if (!key) return;
    let live = true;
    quotePremium(productId, {
      sumAssuredAmount: Number(sumAssuredAmount),
      sumAssuredCurrency: sumAssuredCurrency || 'TZS',
      dateOfBirth: dateOfBirth as string,
      sex: sex as PremiumQuoteRequest['sex'],
      // UNKNOWN is a real cell in the rate table, not a missing value: a person whose smoking is not
      // recorded is priced on the UNKNOWN row, which is why this defaults rather than refusing.
      smokerStatus: smokerStatus ?? 'UNKNOWN',
      occupationClass: occupationClass || DEFAULT_OCCUPATION_CLASS,
      frequency: frequency as PremiumQuoteRequest['frequency'],
      ...(policyTermMonths ? { policyTermMonths: Number(policyTermMonths) } : {}),
    }).then(
      (quote) => live && setLoaded({ key, quote }),
      (error: unknown) => live && setLoaded({ key, error }),
    );
    return () => {
      live = false;
    };
  }, [key, productId, sumAssuredAmount, sumAssuredCurrency, frequency, policyTermMonths, dateOfBirth, sex,
      smokerStatus, occupationClass]);

  if (!ready) return { state: 'incomplete', missing };
  // Derived, not reset in the effect: an answer for other inputs is no answer for these.
  if (!loaded || loaded.key !== key) return { state: 'loading' };
  if (loaded.error !== undefined) return { state: 'refused', error: loaded.error };
  return {
    state: 'quoted',
    quote: loaded.quote as PremiumQuoteView,
    assumedOccupationClass: !occupationClass,
  };
}
