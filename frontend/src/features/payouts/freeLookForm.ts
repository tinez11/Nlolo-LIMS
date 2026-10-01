import { z } from 'zod';

/**
 * The server's own `@Pattern` on a deduction amount, copied exactly.
 *
 * Deliberately NOT `lib/money`'s `AMOUNT_PATTERN`, which permits a leading minus. A negative
 * deduction would ADD to the refund — the one direction this form must not be able to move money —
 * and the backend rejects it, so accepting it here would only produce a 400 the person cannot
 * explain.
 */
const DEDUCTION_AMOUNT = /^\d+(\.\d{1,2})?$/;

/**
 * Cancelling a policy inside its free-look window, and what the insurer withholds.
 *
 * Deductions are ITEMISED, never a percentage: a customer exercising a statutory right to walk
 * away is owed an explanation of every shilling withheld, and the server stores a description per
 * line for exactly that reason.
 *
 * The amount stays a STRING the whole way to the wire. Parsing it to a number here would round a
 * figure the customer may check to the shilling, and the server's own `@Pattern` rejects anything
 * with more than two decimal places — so the same rule is enforced here, in the same shape, rather
 * than letting a submit bounce off a 400.
 */

export interface FreeLookDeductionValues {
  description: string;
  amount: string;
}

export interface FreeLookValues {
  payeeRef: string;
  deductions: FreeLookDeductionValues[];
}

export function blankFreeLook(): FreeLookValues {
  return { payeeRef: '', deductions: [] };
}

export function blankDeduction(): FreeLookDeductionValues {
  return { description: '', amount: '' };
}

export const freeLookSchema = z.object({
  payeeRef: z
    .string()
    .trim()
    .min(1, 'A free-look refund needs a payee reference')
    .max(200, 'A payee reference is at most 200 characters'),
  deductions: z.array(
    z.object({
      description: z
        .string()
        .trim()
        .min(1, 'Every deduction needs a description')
        .max(200, 'A description is at most 200 characters'),
      amount: z.string().regex(DEDUCTION_AMOUNT, 'Enter an amount like 8000.00'),
    }),
  ),
});
