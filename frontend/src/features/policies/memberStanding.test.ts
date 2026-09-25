import { describe, expect, it } from 'vitest';
import { exitReasonLabel, exitSummary } from './memberStanding';

describe('exitSummary', () => {
  it('says a death claim was paid, and when cover ended', () => {
    const summary = exitSummary({ exitReason: 'CLAIM_SETTLED', leftOn: '2026-09-23' });
    expect(summary).toMatch(/^Death claim paid · left /);
    expect(summary).toContain('2026');
  });

  it('tells a repaid loan apart from a death', () => {
    // The distinction the roll could not draw: one owes a refund, the other was a paid claim.
    expect(exitReasonLabel('SETTLED_EARLY')).toBe('Loan repaid early');
    expect(exitReasonLabel('CLAIM_SETTLED')).toBe('Death claim paid');
  });

  it('is empty for a member who has not left', () => {
    expect(exitSummary({ exitReason: null, leftOn: null })).toBeNull();
  });
});
