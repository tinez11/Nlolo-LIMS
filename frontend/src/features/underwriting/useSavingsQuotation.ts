import { useEffect, useState } from 'react';
import { quoteProductSavings } from '@/api/accumulation';
import type { SavingsQuoteView } from '@/api/types';

/** What a savings quotation needs before it can be asked for at all. */
export interface SavingsQuotationInputs {
  productId: string;
  productVersionId: string;
  /** The deposit, or an account's opening balance. The form's "sum assured" field, which is what it means here. */
  amount: string;
  currency: string;
  /** A deposit's term. Blank is allowed: the server then names the terms it offers instead of pricing one. */
  termMonths: string;
  /** The proposed commencement, or blank for today. */
  asOf: string;
  /**
   * A term account's (2026-10-21): the rate the case would name, how often the contribution is paid, and what the
   * customer wants at maturity. A target asks the server for the contribution that reaches it, in place of `amount`.
   */
  termAccount?: { ratePercent: string; frequency: string; targetAmount: string } | undefined;
}

export type SavingsQuotation =
  | { state: 'incomplete'; missing: string[] }
  | { state: 'loading' }
  | { state: 'quoted'; quote: SavingsQuoteView }
  | { state: 'refused'; error: unknown };

/**
 * What a savings product would pay, read from the server as the form changes (2026-10-16).
 *
 * <p><b>Why a second hook.</b> {@code useQuotation} asks for a premium against a base rate table, which is the only
 * thing the premium-quote endpoint can price. A savings account and a fixed-term deposit have no sum assured and no
 * premium rate, so that endpoint answered every one of them with a red 422 -- "carries no base rate table" -- on
 * products that are working exactly as designed. This asks the question those products actually answer.
 *
 * <p><b>It creates nothing:</b> no account, no deposit period, no interest, no ledger entry.
 *
 * <p><b>A refusal inside a 200.</b> A term the grid does not offer, or an amount below the smallest band, comes back
 * as a priced-nothing quote carrying {@code refusal} and {@code offeredTermMonths} -- not as an error -- because the
 * useful answer to "9 months" is "this deposit runs 3, 6 or 12". Only a product that keeps no account at all throws.
 */
export function useSavingsQuotation(inputs: SavingsQuotationInputs): SavingsQuotation {
  const { productId, productVersionId, amount, currency, termMonths, asOf, termAccount } = inputs;
  const rate = termAccount?.ratePercent.trim() ?? '';
  const frequency = termAccount?.frequency ?? '';
  const target = termAccount?.targetAmount.trim() ?? '';
  // A target, when one is given, is what is asked about: the contribution is the answer.
  const solving = !!termAccount && target !== '' && Number(target) > 0;

  const missing: string[] = [];
  if (!productId || !productVersionId) missing.push('a product');
  // `!(n > 0)`: "5,000,000" is NaN, which `<= 0` let through to the server as a null amount.
  if (!solving && (!amount || !(Number(amount) > 0))) {
    missing.push(termAccount ? 'a contribution, or the amount wanted at maturity, in digits' : 'an amount in digits');
  }
  if (termMonths && !(/^\d+$/.test(termMonths.trim()) && Number(termMonths) > 0)) missing.push('a term in whole months');
  if (termAccount && rate !== '' && Number.isNaN(Number(rate))) missing.push('a rate in digits');

  const ready = missing.length === 0;
  const key = ready
    ? [productId, productVersionId, solving ? '' : amount, currency, termMonths, asOf, rate, frequency, solving ? target : ''].join('|')
    : '';

  const [loaded, setLoaded] = useState<{ key: string; quote?: SavingsQuoteView; error?: unknown } | null>(null);

  useEffect(() => {
    if (!key) return undefined;
    let live = true;
    quoteProductSavings(productId, productVersionId, {
      ...(solving ? { targetAmount: Number(target) } : { amount: Number(amount) }),
      ...(currency ? { currency } : {}),
      ...(termMonths ? { termMonths: Number(termMonths) } : {}),
      ...(asOf ? { asOf } : {}),
      ...(rate ? { ratePercent: Number(rate) } : {}),
      ...(frequency ? { frequency: frequency as 'MONTHLY' | 'QUARTERLY' | 'ANNUALLY' | 'SINGLE' } : {}),
    }).then(
      (quote) => live && setLoaded({ key, quote }),
      (error: unknown) => live && setLoaded({ key, error }),
    );
    return () => {
      live = false;
    };
  }, [key, productId, productVersionId, amount, currency, termMonths, asOf, rate, frequency, target, solving]);

  if (!ready) return { state: 'incomplete', missing };
  // Derived, not reset in the effect: an answer for other inputs is no answer for these.
  if (!loaded || loaded.key !== key) return { state: 'loading' };
  if (loaded.error !== undefined) return { state: 'refused', error: loaded.error };
  return { state: 'quoted', quote: loaded.quote as SavingsQuoteView };
}
