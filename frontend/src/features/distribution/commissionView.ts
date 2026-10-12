import type { CommissionAccrualView, CommissionStatementView } from '@/api/types';
import { isValidAmount, type Money } from '@/lib/money';

/**
 * The arithmetic and wording behind the agent commission view (spec 2026-10-12).
 *
 * lib/money holds that the backend computes every total, and these figures respect that: each is a sum of
 * statement totals the backend already computed, net of clawbacks, grouped by month and status. The user
 * chose this over a summary endpoint (D2). Exact, through BigInt cents -- never a JS number.
 */

const DAR_ES_SALAAM = 'Africa/Dar_es_Salaam';

/** Today in Dar es Salaam as `YYYY-MM-DD` -- not the browser's zone, not UTC. */
export function darToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: DAR_ES_SALAAM }).format(now);
}

export interface CommissionTotals {
  currencyCode: string;
  earnedThisMonth: Money;
  earnedThisYear: Money;
  /** All time (D4). */
  paidOut: Money;
  /** Closed, requested or failed: owed and not yet paid (D3). */
  awaitingPayout: Money;
}

const AWAITING: ReadonlySet<CommissionStatementView['status']> = new Set([
  'CLOSED',
  'PAYOUT_REQUESTED',
  'PAYOUT_FAILED',
]);

function toCents(amount: string): bigint | null {
  if (!isValidAmount(amount)) return null;
  const negative = amount.startsWith('-');
  const [whole, fraction = ''] = (negative ? amount.slice(1) : amount).split('.');
  const cents = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
  return negative ? -cents : cents;
}

function toMoney(cents: bigint, currencyCode: string): Money {
  const sign = cents < 0n ? '-' : '';
  const magnitude = cents < 0n ? -cents : cents;
  return { amount: `${sign}${magnitude / 100n}.${String(magnitude % 100n).padStart(2, '0')}`, currencyCode };
}

/**
 * The four headline figures, one entry per currency in order of first appearance -- currencies are never
 * added together. A statement whose amount is not a valid decimal is left out rather than guessed at.
 * `today` is `darToday()`'s `YYYY-MM-DD`.
 */
export function commissionTotals(
  statements: readonly CommissionStatementView[],
  today: string,
): CommissionTotals[] {
  const month = today.slice(0, 7);
  const yearPrefix = `${today.slice(0, 4)}-`;
  const sums = new Map<string, { month: bigint; year: bigint; paid: bigint; awaiting: bigint }>();
  for (const s of statements) {
    const cents = toCents(s.totalAmount.amount);
    if (cents === null) continue;
    const code = s.totalAmount.currencyCode;
    const sum = sums.get(code) ?? { month: 0n, year: 0n, paid: 0n, awaiting: 0n };
    if (s.period === month) sum.month += cents;
    if (s.period.startsWith(yearPrefix)) sum.year += cents;
    if (s.status === 'PAID') sum.paid += cents;
    if (AWAITING.has(s.status)) sum.awaiting += cents;
    sums.set(code, sum);
  }
  return [...sums].map(([code, sum]) => ({
    currencyCode: code,
    earnedThisMonth: toMoney(sum.month, code),
    earnedThisYear: toMoney(sum.year, code),
    paidOut: toMoney(sum.paid, code),
    awaitingPayout: toMoney(sum.awaiting, code),
  }));
}

export interface StatementMonth {
  /** The statement id, or `none:<period>` for the current month before anything has accrued in it. */
  key: string;
  period: string;
  statement: CommissionStatementView | null;
}

/**
 * The months to list, newest first. The current month is always there: a month with no commission yet is
 * an answer ("nothing yet"), not a missing row.
 */
export function statementMonths(
  statements: readonly CommissionStatementView[],
  currentPeriod: string,
): StatementMonth[] {
  const months: StatementMonth[] = statements.map((s) => ({ key: s.statementId, period: s.period, statement: s }));
  if (!statements.some((s) => s.period === currentPeriod)) {
    months.push({ key: `none:${currentPeriod}`, period: currentPeriod, statement: null });
  }
  return months.sort(
    (a, b) =>
      b.period.localeCompare(a.period) ||
      (a.statement?.totalAmount.currencyCode ?? '').localeCompare(b.statement?.totalAmount.currencyCode ?? ''),
  );
}

const MONTH_LABEL = new Intl.DateTimeFormat('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' });

/** `2026-10` → `October 2026`. */
export function monthLabel(period: string): string {
  const [year, month] = period.split('-').map(Number);
  return MONTH_LABEL.format(new Date(Date.UTC(year, month - 1, 1)));
}

const TIER_LABELS: Record<CommissionAccrualView['tierType'], string> = {
  FIRST_YEAR: 'First year',
  RENEWAL: 'Renewal',
  OVERRIDE: 'Override',
  SUPERVISOR_OVERRIDE: 'Supervisor override',
  THRESHOLD_BONUS: 'Threshold bonus',
};

/** What a line is, in words. A reversal is a clawback of its tier. */
export function accrualTypeLabel(line: Pick<CommissionAccrualView, 'tierType' | 'reversesAccrualId'>): string {
  const tier = TIER_LABELS[line.tierType];
  return line.reversesAccrualId ? `Clawback (${tier.toLowerCase()})` : tier;
}
