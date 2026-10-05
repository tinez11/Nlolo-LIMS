import { z } from 'zod';

/**
 * The fund register's forms (product step 6). The checks mirror FundRegister and the unitlinked schema's
 * CHECKs, so the form refuses what the server would. A price is a decimal string up to six places and
 * never a binary float: it is sent as typed.
 */

const PRICE = /^\d{1,13}(\.\d{1,6})?$/;

export const ASSET_CLASSES = ['EQUITY', 'BOND', 'MONEY_MARKET', 'BALANCED'] as const;

export const ASSET_CLASS_LABELS: Record<(typeof ASSET_CLASSES)[number], string> = {
  EQUITY: 'Equity',
  BOND: 'Bond',
  MONEY_MARKET: 'Money market',
  BALANCED: 'Balanced',
};

export const createFundSchema = z.object({
  code: z
    .string()
    .trim()
    .toUpperCase()
    .regex(/^[A-Z0-9][A-Z0-9_-]{1,19}$/, 'A fund code is 2 to 20 letters, digits, - or _'),
  name: z.string().trim().min(1, 'A fund needs a name').max(120),
  currency: z.string().trim().toUpperCase().regex(/^[A-Z]{3}$/, 'A three-letter currency code, for example TZS'),
  assetClass: z.enum(ASSET_CLASSES, { message: 'Choose the asset class' }),
  annualManagementChargePercent: z
    .string()
    .trim()
    .regex(/^\d{1,2}(\.\d{1,4})?$/, 'A percent with at most four decimals, for example 1.5'),
  cutOffTime: z.string().trim().regex(/^([01]\d|2[0-3]):[0-5]\d$/, 'The daily cut-off as HH:mm, East Africa Time'),
});
export type CreateFundValues = z.infer<typeof createFundSchema>;

export const proposePriceSchema = z.object({
  valuationDate: z.string().trim().min(1, 'A price is for a valuation date'),
  price: z
    .string()
    .trim()
    .regex(PRICE, 'A price with at most six decimals, for example 1.034500')
    .refine((v) => Number(v) > 0, 'A fund price is greater than zero'),
  moveReason: z.string().trim().max(500),
});
export type ProposePriceValues = z.infer<typeof proposePriceSchema>;

export const correctionSchema = z.object({
  price: z
    .string()
    .trim()
    .regex(PRICE, 'A price with at most six decimals, for example 1.034500')
    .refine((v) => Number(v) > 0, 'A fund price is greater than zero'),
  reason: z.string().trim().min(1, 'A correction says why the approved price was wrong').max(500),
});
export type CorrectionValues = z.infer<typeof correctionSchema>;

/** How far a proposed price moves from the last approved one, in percent; null when there is none to compare. */
export function movePercent(price: string, lastApproved: string | undefined): number | null {
  const p = Number(price);
  const last = lastApproved === undefined ? NaN : Number(lastApproved);
  if (!(p > 0) || !(last > 0)) return null;
  return ((p - last) / last) * 100;
}
