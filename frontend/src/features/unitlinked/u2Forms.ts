import { z } from 'zod';
import type { FundShare, UnitLinkedOptionsView, SwitchRequestBody, UnitLinkedTopUpRequest, WithdrawalRequestBody } from '@/api/types';

/**
 * The U2 forms on a unit-linked policy (product step 6): a switch, a partial withdrawal, a top-up, a premium
 * redirection and an on-demand statement. Each check is the server's, in its words (SplitRules, Switches,
 * Withdrawals, TopUps, Statements), so the form refuses what the server would. Amounts stay decimal strings.
 */

const AMOUNT = /^\d{1,15}(\.\d{1,2})?$/;

/** The server's money format: `String.format("%,.2f")`. */
export function money(amount: number): string {
  return amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

const row = z.object({ fundCode: z.string(), percent: z.string().trim() });
export type ShareRow = z.infer<typeof row>;

const isWholePercent = (s: string) => /^\d{1,3}$/.test(s) && Number(s) >= 1 && Number(s) <= 100;

/** Rows with a percent typed in; a blank row is a fund not chosen. */
function filled(rows: ShareRow[]): ShareRow[] {
  return rows.filter((r) => r.percent !== '');
}

function checkSplit(rows: ShareRow[], path: string, ctx: z.RefinementCtx) {
  const chosen = filled(rows);
  if (chosen.length === 0) {
    ctx.addIssue({ code: 'custom', path: [path], message: 'A unit-linked split says how each premium is divided across the funds' });
    return;
  }
  if (chosen.some((r) => !isWholePercent(r.percent))) {
    ctx.addIssue({ code: 'custom', path: [path], message: "Each fund's share is a whole percent from 1 to 100" });
    return;
  }
  const total = chosen.reduce((sum, r) => sum + Number(r.percent), 0);
  if (total !== 100) {
    ctx.addIssue({ code: 'custom', path: [path], message: `The fund split totals ${total}%; it must total 100%` });
  }
}

const toShares = (rows: ShareRow[]): FundShare[] =>
  filled(rows).map((r) => ({ fundCode: r.fundCode, percent: Number(r.percent) }));

// ---- Premium redirection ----

export const splitSchema = z
  .object({ shares: z.array(row) })
  .superRefine((v, ctx) => checkSplit(v.shares, 'shares', ctx));
export type SplitValues = z.infer<typeof splitSchema>;

export const toSplit = (v: SplitValues): FundShare[] => toShares(v.shares);

// ---- Switch ----

export const switchSchema = z
  .object({ out: z.array(row), into: z.array(row) })
  .superRefine((v, ctx) => {
    const out = filled(v.out);
    if (out.length === 0) {
      ctx.addIssue({ code: 'custom', path: ['out'], message: 'A switch names at least one fund to move out of' });
    } else if (out.some((r) => !isWholePercent(r.percent))) {
      ctx.addIssue({ code: 'custom', path: ['out'], message: "Each fund's share is a whole percent from 1 to 100" });
    }
    checkSplit(v.into, 'into', ctx);
  });
export type SwitchValues = z.infer<typeof switchSchema>;

export const toSwitchRequest = (v: SwitchValues): SwitchRequestBody => ({ out: toShares(v.out), into: toShares(v.into) });

// ---- Partial withdrawal ----

const namedRow = z.object({ fundCode: z.string(), amount: z.string().trim() });

export function withdrawalSchema(minimum: number | null, currency: string) {
  return z
    .object({ grossAmount: z.string().trim(), funds: z.array(namedRow), payeeRef: z.string().trim() })
    .superRefine((v, ctx) => {
      const gross = Number(v.grossAmount);
      if (!AMOUNT.test(v.grossAmount) || !(gross > 0)) {
        ctx.addIssue({ code: 'custom', path: ['grossAmount'], message: 'A withdrawal is a gross amount above zero' });
      } else if (minimum !== null && gross < minimum) {
        ctx.addIssue({ code: 'custom', path: ['grossAmount'], message: `A withdrawal is at least ${money(minimum)} ${currency}` });
      }
      if (v.payeeRef === '') ctx.addIssue({ code: 'custom', path: ['payeeRef'], message: 'A withdrawal needs the payee to pay' });
      const named = v.funds.filter((f) => f.amount !== '');
      if (named.length === 0) return;
      if (named.some((f) => !AMOUNT.test(f.amount) || !(Number(f.amount) > 0))) {
        ctx.addIssue({ code: 'custom', path: ['funds'], message: "Each named fund's amount is above zero" });
        return;
      }
      const sum = named.reduce((s, f) => s + Number(f.amount), 0);
      if (gross > 0 && Math.abs(sum - gross) > 0.001) {
        ctx.addIssue({
          code: 'custom',
          path: ['funds'],
          message: `The named funds' amounts total ${money(sum)}; they must total the withdrawal of ${money(gross)}`,
        });
      }
    });
}
export type WithdrawalValues = z.infer<ReturnType<typeof withdrawalSchema>>;

export const toWithdrawalRequest = (v: WithdrawalValues): WithdrawalRequestBody => ({
  grossAmount: v.grossAmount,
  funds: v.funds.filter((f) => f.amount !== '').map((f) => ({ fundCode: f.fundCode, amount: f.amount })),
  payeeRef: v.payeeRef,
});

// ---- Top-up ----

export function topUpSchema(minimum: number | null, currency: string) {
  return z
    .object({ amount: z.string().trim(), payerRef: z.string().trim(), split: z.array(row) })
    .superRefine((v, ctx) => {
      const amount = Number(v.amount);
      if (!AMOUNT.test(v.amount) || !(amount > 0) || (minimum !== null && amount < minimum)) {
        ctx.addIssue({
          code: 'custom',
          path: ['amount'],
          message: minimum !== null ? `A top-up is at least ${money(minimum)} ${currency}` : 'A top-up is an amount above zero',
        });
      }
      if (v.payerRef === '') ctx.addIssue({ code: 'custom', path: ['payerRef'], message: 'A top-up needs the payer to collect it from' });
      // Its own split is optional: none typed in means the policy's split in force.
      if (filled(v.split).length > 0) checkSplit(v.split, 'split', ctx);
    });
}
export type TopUpValues = z.infer<ReturnType<typeof topUpSchema>>;

export const toTopUpRequest = (v: TopUpValues): UnitLinkedTopUpRequest => ({
  amount: v.amount,
  payerRef: v.payerRef,
  split: toShares(v.split),
});

// ---- On-demand statement ----

export function statementSchema(today: string) {
  return z
    .object({ from: z.string().trim(), to: z.string().trim() })
    .superRefine((v, ctx) => {
      if (v.from === '' || v.to === '') {
        ctx.addIssue({ code: 'custom', path: ['to'], message: 'A statement needs the first and last day of its period' });
        return;
      }
      if (v.to > today) ctx.addIssue({ code: 'custom', path: ['to'], message: 'A statement period ends today at the latest' });
      else if (v.from > v.to) {
        ctx.addIssue({ code: 'custom', path: ['from'], message: 'A statement period starts on or before its last day' });
      }
    });
}
export type StatementValues = z.infer<ReturnType<typeof statementSchema>>;

/** Whether the version offers any U2 action on this policy. */
export function offers(options: UnitLinkedOptionsView | null | undefined) {
  return {
    switching: options?.freeSwitchesPerYear != null,
    withdrawals: options?.minimumWithdrawal != null,
    topUps: options?.topUpAllocationPercent != null,
  };
}
