import { describe, expect, it } from 'vitest';
import { canAccept, canDecide, differenceLabel, previousMonth, RUN_STATUS_LABEL } from './enginePeriod';

describe('enginePeriod', () => {
  it('names every run status', () => {
    expect(RUN_STATUS_LABEL.VALIDATED).toBe('Validated — awaiting approval');
    expect(RUN_STATUS_LABEL.POSTED).toBe('Posted');
  });

  it('lets a finance approver decide a validated run, but not one they uploaded', () => {
    const run = { status: 'VALIDATED', uploadedBy: 'finance-one' };
    expect(canDecide(run, 'finance-approver', true)).toBe(true);
    expect(canDecide(run, 'finance-one', true)).toBe(false);
    expect(canDecide(run, 'finance-approver', false)).toBe(false);
    expect(canDecide({ ...run, status: 'REJECTED' }, 'finance-approver', true)).toBe(false);
  });

  it('lets a second approver accept an explanation, never its author', () => {
    const row = { status: 'EXPLAINED', explainedBy: 'finance-one' };
    expect(canAccept(row, 'finance-approver', true)).toBe(true);
    expect(canAccept(row, 'finance-one', true)).toBe(false);
    expect(canAccept({ ...row, status: 'EXCEPTION' }, 'finance-approver', true)).toBe(false);
  });

  it('says what a difference is', () => {
    expect(differenceLabel({ status: 'AGREED', difference: 0.5 })).toBe('Agreed (0.50 rounding)');
    expect(differenceLabel({ status: 'AGREED', difference: 0 })).toBe('Agreed');
    expect(differenceLabel({ status: 'EXCEPTION', difference: -5 })).toBe('Differs by 5.00 — explain it, or post a replacement run');
    expect(differenceLabel({ status: 'EXPLAINED', difference: 5 })).toBe('Explained — awaiting acceptance by a second person');
    expect(differenceLabel({ status: 'ACCEPTED', difference: 5 })).toBe('Accepted');
  });

  it('defaults to the month that just ended, in Dar es Salaam', () => {
    expect(previousMonth(new Date('2026-10-06T10:00:00+03:00'))).toBe('2026-09');
    expect(previousMonth(new Date('2026-01-01T00:30:00+03:00'))).toBe('2025-12');
  });
});
