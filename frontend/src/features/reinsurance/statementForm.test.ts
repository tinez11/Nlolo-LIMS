import { describe, expect, it } from 'vitest';
import { endedQuarters, settlementSide, STATEMENT_STATUS_LABEL } from './statementForm';

describe('statementForm', () => {
  it('offers the quarters that have ended since the treaty began, newest first', () => {
    expect(endedQuarters('2026-02-10', null, new Date('2026-10-06T10:00:00+03:00'))).toEqual(['2026-Q3', '2026-Q2', '2026-Q1']);
    expect(endedQuarters('2026-09-01', null, new Date('2026-09-30T23:00:00+03:00'))).toEqual([]);
    // 30 September 22:30 UTC is already 1 October in Dar es Salaam: Q3 has ended there
    expect(endedQuarters('2026-09-01', null, new Date('2026-09-30T22:30:00Z'))).toEqual(['2026-Q3']);
  });

  it('stops at the treaty end', () => {
    expect(endedQuarters('2026-01-01', '2026-05-31', new Date('2026-12-01T10:00:00+03:00'))).toEqual(['2026-Q2', '2026-Q1']);
  });

  it('says which way the current account falls', () => {
    expect(settlementSide({ owedToUs: '28560000.00', owedByUs: '0.00' })).toBe('The reinsurer owes us 28,560,000.00');
    expect(settlementSide({ owedToUs: '0.00', owedByUs: '120000.00' })).toBe('We owe the reinsurer 120,000.00');
    expect(settlementSide({ owedToUs: '0.00', owedByUs: '0.00' })).toBe('Settled: nothing owed either way');
  });

  it('names every status', () => {
    expect(STATEMENT_STATUS_LABEL.APPROVED).toBe('Posted');
    expect(STATEMENT_STATUS_LABEL.SUBMITTED).toBe('Awaiting approval');
  });
});
