import { describe, expect, it } from 'vitest';
import type { CommissionStatementView } from '@/api/types';
import {
  accrualTypeLabel,
  commissionTotals,
  darToday,
  monthLabel,
  statementMonths,
} from './commissionView';

function statement(
  period: string,
  amount: string,
  status: CommissionStatementView['status'],
  currencyCode = 'TZS',
): CommissionStatementView {
  return {
    statementId: `${period}-${status}-${currencyCode}`,
    agentId: 'a1',
    period,
    totalAmount: { amount, currencyCode },
    status,
  };
}

describe('darToday', () => {
  it('is the Dar es Salaam date, which turns three hours before UTC', () => {
    expect(darToday(new Date('2026-10-31T21:30:00Z'))).toBe('2026-11-01');
    expect(darToday(new Date('2026-10-31T20:59:59Z'))).toBe('2026-10-31');
  });
});

describe('commissionTotals', () => {
  const today = '2026-10-12';

  it('sums this month, this year, paid and awaiting from the statements', () => {
    const [tzs] = commissionTotals(
      [
        statement('2026-10', '184000.00', 'OPEN'),
        statement('2026-09', '1022500.00', 'CLOSED'),
        statement('2026-08', '500.50', 'PAYOUT_REQUESTED'),
        statement('2026-07', '1000.00', 'PAID'),
        statement('2025-12', '7000.00', 'PAID'),
      ],
      today,
    );
    expect(tzs.earnedThisMonth).toEqual({ amount: '184000.00', currencyCode: 'TZS' });
    expect(tzs.earnedThisYear).toEqual({ amount: '1208000.50', currencyCode: 'TZS' });
    expect(tzs.paidOut).toEqual({ amount: '8000.00', currencyCode: 'TZS' });
    expect(tzs.awaitingPayout).toEqual({ amount: '1023000.50', currencyCode: 'TZS' });
  });

  it('counts a failed payout as still awaiting', () => {
    const [tzs] = commissionTotals([statement('2026-09', '300.00', 'PAYOUT_FAILED')], today);
    expect(tzs.awaitingPayout.amount).toBe('300.00');
    expect(tzs.paidOut.amount).toBe('0.00');
  });

  it('nets clawbacks: a negative statement reduces every figure it belongs to', () => {
    const [tzs] = commissionTotals(
      [statement('2026-10', '-10000.00', 'OPEN'), statement('2026-09', '25000.00', 'CLOSED')],
      today,
    );
    expect(tzs.earnedThisMonth.amount).toBe('-10000.00');
    expect(tzs.earnedThisYear.amount).toBe('15000.00');
  });

  it('keeps currencies apart', () => {
    const totals = commissionTotals(
      [statement('2026-10', '100.00', 'OPEN', 'TZS'), statement('2026-10', '5.00', 'OPEN', 'USD')],
      today,
    );
    expect(totals.map((t) => [t.currencyCode, t.earnedThisMonth.amount])).toEqual([
      ['TZS', '100.00'],
      ['USD', '5.00'],
    ]);
  });

  it('is empty when nothing has been earned', () => {
    expect(commissionTotals([], today)).toEqual([]);
  });

  it('adds exactly, past the precision of a JS number', () => {
    const [tzs] = commissionTotals(
      [statement('2026-10', '9007199254740993.99', 'OPEN'), statement('2026-09', '0.01', 'OPEN')],
      today,
    );
    expect(tzs.earnedThisYear.amount).toBe('9007199254740994.00');
  });
});

describe('statementMonths', () => {
  it('always lists the current month, newest first, even with no statement for it', () => {
    const months = statementMonths([statement('2026-08', '1.00', 'PAID')], '2026-10');
    expect(months.map((m) => [m.period, m.statement === null])).toEqual([
      ['2026-10', true],
      ['2026-08', false],
    ]);
  });

  it('does not add a second row when the current month has a statement', () => {
    const months = statementMonths([statement('2026-10', '1.00', 'OPEN')], '2026-10');
    expect(months).toHaveLength(1);
    expect(months[0].statement).not.toBeNull();
  });
});

describe('monthLabel', () => {
  it('names the month', () => {
    expect(monthLabel('2026-10')).toBe('October 2026');
    expect(monthLabel('2027-01')).toBe('January 2027');
  });
});

describe('accrualTypeLabel', () => {
  it('names every tier', () => {
    expect(accrualTypeLabel({ tierType: 'FIRST_YEAR' })).toBe('First year');
    expect(accrualTypeLabel({ tierType: 'RENEWAL' })).toBe('Renewal');
    expect(accrualTypeLabel({ tierType: 'OVERRIDE' })).toBe('Override');
    expect(accrualTypeLabel({ tierType: 'SUPERVISOR_OVERRIDE' })).toBe('Supervisor override');
  });

  it('calls a reversal a clawback, and says of what', () => {
    expect(accrualTypeLabel({ tierType: 'FIRST_YEAR', reversesAccrualId: 'x' })).toBe('Clawback (first year)');
    expect(accrualTypeLabel({ tierType: 'RENEWAL', reversesAccrualId: null })).toBe('Renewal');
  });
});
