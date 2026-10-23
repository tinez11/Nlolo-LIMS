import { useEffect, useState } from 'react';
import { quoteFuneral } from '@/api/funeral';
import type { FuneralQuoteRequest, FuneralQuoteView } from '@/api/types';
import { toApiError, type ApiError } from '@/lib/apiError';
import type { FuneralRoleName } from '@/features/products/funeralSchema';

/** One life as the quote endpoint takes it. The main member is the applicant, added by the hook. */
export interface FuneralDependantInput {
  role: FuneralRoleName | '';
  fullName: string;
  dateOfBirth: string;
  student: boolean;
}

export interface FuneralQuotationInputs {
  productId: string;
  productVersionId: string;
  planCode: string;
  frequency: string;
  /** The proposed commencement, or blank for today -- the day every age on the quote is taken at. */
  commencement: string;
  mainMember: { name: string; dateOfBirth: string | null } | null;
  dependants: FuneralDependantInput[];
}

export type FuneralQuotation =
  | { state: 'incomplete'; missing: string[] }
  | { state: 'loading' }
  | { state: 'quoted'; quote: FuneralQuoteView }
  | { state: 'refused'; error: ApiError };

/**
 * A family priced on one funeral plan, read from the server as the form changes.
 *
 * <p>Lifted out of {@code FuneralLivesFields} (2026-10-16) so the figures can live in the Quotation panel with every
 * other product's, instead of under the family fields where only a funeral plan ever showed a price. The request is
 * unchanged: the plan, the frequency, the commencement and a line per life, with the main member first.
 *
 * <p><b>Debounced</b>, because it fires on every keystroke in a name or a date of birth. 400ms, as before.
 *
 * <p>Nothing here prices anything: every figure on the quote is the server's, from the plan's own premium grid.
 */
/**
 * The funeral-quote request for this family on {@code planCode} -- the one the hook sends, exported so the same family
 * can be priced on the product's other plans side by side (2026-10-23).
 */
export function funeralQuoteRequest(inputs: FuneralQuotationInputs, planCode = inputs.planCode): FuneralQuoteRequest {
  const { frequency, commencement, mainMember, dependants } = inputs;
  return {
    planCode,
    frequency: frequency as 'MONTHLY' | 'QUARTERLY' | 'ANNUALLY',
    ...(commencement ? { asOf: commencement } : {}),
    lives: [
      { role: 'MAIN_MEMBER', name: mainMember?.name ?? '', dateOfBirth: mainMember?.dateOfBirth ?? null },
      ...dependants.map((d) => ({
        role: d.role as FuneralRoleName,
        name: d.fullName,
        dateOfBirth: d.dateOfBirth || null,
        student: d.student,
      })),
    ],
  };
}

export function useFuneralQuotation(inputs: FuneralQuotationInputs): FuneralQuotation {
  const { productId, productVersionId, planCode, frequency, commencement, mainMember, dependants } = inputs;

  const missing: string[] = [];
  if (!planCode) missing.push('a plan');
  if (!frequency) missing.push('a payment frequency');
  if (!mainMember?.dateOfBirth) missing.push("the main member's date of birth");

  const ready = missing.length === 0 && Boolean(productId) && Boolean(productVersionId);
  // The lives are compared by VALUE: a new array with the same rows in it is not a new question.
  const lives = JSON.stringify(dependants);
  const key = ready
    ? [productId, productVersionId, planCode, frequency, commencement, mainMember?.name, mainMember?.dateOfBirth, lives].join('|')
    : '';

  const [loaded, setLoaded] = useState<{ key: string; quote?: FuneralQuoteView; error?: ApiError } | null>(null);

  useEffect(() => {
    if (!key || !mainMember?.dateOfBirth) return undefined;
    let live = true;
    const timer = setTimeout(() => {
      quoteFuneral(productId, productVersionId, {
        planCode,
        frequency: frequency as 'MONTHLY' | 'QUARTERLY' | 'ANNUALLY',
        ...(commencement ? { asOf: commencement } : {}),
        lives: [
          { role: 'MAIN_MEMBER', name: mainMember.name, dateOfBirth: mainMember.dateOfBirth },
          ...(JSON.parse(lives) as FuneralDependantInput[]).map((d) => ({
            role: d.role as FuneralRoleName,
            name: d.fullName,
            dateOfBirth: d.dateOfBirth || null,
            student: d.student,
          })),
        ],
      }).then(
        (quote) => live && setLoaded({ key, quote }),
        (error: unknown) => live && setLoaded({ key, error: toApiError(error) }),
      );
    }, 400);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [key, productId, productVersionId, planCode, frequency, commencement, lives, mainMember?.name, mainMember?.dateOfBirth]);

  if (!ready) return { state: 'incomplete', missing };
  if (!loaded || loaded.key !== key) return { state: 'loading' };
  if (loaded.error !== undefined) return { state: 'refused', error: loaded.error };
  return { state: 'quoted', quote: loaded.quote as FuneralQuoteView };
}
