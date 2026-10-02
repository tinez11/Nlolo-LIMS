import { z } from 'zod';

/**
 * The money-moving forms on a savings account (product step 3).
 *
 * Every amount stays a STRING to the wire, in the server's own `@Pattern` shape
 * (AccountRequestBodies): parsing to a number here would round a figure the customer checks to the
 * shilling, and the same rule enforced in the same shape is what stops a submit bouncing off a 400.
 */

/** Unsigned: a withdrawal, a top-up or a transfer in moves money ONE way, named by the action. */
const AMOUNT = /^\d+(\.\d{1,2})?$/;
/** Signed: a correction may take money out as well as put it in. */
const SIGNED_AMOUNT = /^-?\d+(\.\d{1,2})?$/;
const AMOUNT_MESSAGE = 'An amount with at most two decimals, for example 25000.00';

const positiveAmount = z
  .string()
  .trim()
  .regex(AMOUNT, AMOUNT_MESSAGE)
  .refine((v) => Number(v) > 0, 'Must be more than zero');

export const withdrawalSchema = z.object({
  amount: positiveAmount,
  payeeRef: z.string().trim().min(1, 'Who is the money paid to?').max(200),
});
export type WithdrawalValues = z.infer<typeof withdrawalSchema>;

export const topUpSchema = z.object({
  amount: positiveAmount,
  payerRef: z.string().trim().min(1, 'Who is the money collected from?').max(200),
});
export type TopUpValues = z.infer<typeof topUpSchema>;

export const transferInSchema = z.object({
  amount: positiveAmount,
  sourceScheme: z.string().trim().min(1, 'Name the scheme the money came from').max(200),
  documentRef: z.string().trim().max(200),
});
export type TransferInValues = z.infer<typeof transferInSchema>;

export const adjustmentSchema = z.object({
  amount: z
    .string()
    .trim()
    .regex(SIGNED_AMOUNT, 'A signed amount with at most two decimals, for example -250.00')
    .refine((v) => Number(v) !== 0, 'An adjustment must move the balance by something'),
  reason: z.string().trim().min(1, 'An adjustment needs a reason').max(500),
});
export type AdjustmentValues = z.infer<typeof adjustmentSchema>;
