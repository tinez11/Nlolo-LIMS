import { describe, expect, it } from 'vitest';
import type { PayoutInstalmentView } from '@/api/types';
import { approveGates, retryGates, reviewGates } from './payoutGates';

const base: PayoutInstalmentView = {
  instalmentId: 'i-1',
  policyNumber: 'POL-1',
  kind: 'SURVIVAL',
  dueDate: '2031-01-15',
  originalAmount: { amount: '100000.00', currencyCode: 'TZS' },
  currentAmount: { amount: '100000.00', currencyCode: 'TZS' },
  restatementReason: null,
  status: 'DUE',
  statusReason: null,
  streamId: null,
  payeeRef: null,
  proofOfLifeMethod: null,
  reviewedBy: null,
  approvedBy: null,
  paymentRunId: null,
  attempts: 0,
};

describe('reviewGates', () => {
  it('passes a DUE survival payout and says proof of life is required', () => {
    const gates = reviewGates(base);
    expect(gates.every((g) => g.ok)).toBe(true);
    expect(gates.map((g) => g.title)).toContain('Proof of life is required');
  });

  it('fails hard on a held payout, in the server’s own words', () => {
    const gates = reviewGates({
      ...base,
      status: 'ON_HOLD',
      statusReason: 'Premiums are not paid up to the due date',
    });
    const held = gates.find((g) => !g.ok)!;
    expect(held.hard).toBe(true);
    expect(held.detail).toBe('Premiums are not paid up to the due date');
  });

  it('asks no proof of life of a maturity', () => {
    expect(reviewGates({ ...base, kind: 'MATURITY' }).map((g) => g.title)).not.toContain(
      'Proof of life is required',
    );
  });

  it('refuses a payout already reviewed', () => {
    const gates = reviewGates({ ...base, status: 'REVIEWED' });
    expect(gates.find((g) => !g.ok)?.detail).toBe('This payout is Reviewed, so it cannot be reviewed.');
  });
});

describe('approveGates', () => {
  it('refuses the reviewer, in the server wording', () => {
    const gates = approveGates({ ...base, status: 'REVIEWED', reviewedBy: 'fin-1' }, 'fin-1');
    const refused = gates.find((g) => !g.ok)!;
    expect(refused.hard).toBe(true);
    expect(refused.detail).toBe(
      'A payout must be approved by someone other than the person who reviewed it (fin-1)',
    );
  });

  it('passes a different approver', () => {
    expect(
      approveGates({ ...base, status: 'REVIEWED', reviewedBy: 'fin-1' }, 'admin-2').every((g) => g.ok),
    ).toBe(true);
  });

  it('does not accuse an unknown viewer of being the reviewer', () => {
    // A token with no subject must not silently pass OR silently fail the two-person rule by
    // comparing undefined against undefined.
    expect(approveGates({ ...base, status: 'REVIEWED', reviewedBy: 'fin-1' }, undefined).every((g) => g.ok)).toBe(
      true,
    );
  });
});

describe('retryGates', () => {
  it('passes a FAILED payout', () => {
    expect(retryGates({ ...base, status: 'FAILED' }).every((g) => g.ok)).toBe(true);
  });

  it('refuses an IN_DOUBT payout, because retrying it could pay twice', () => {
    const gates = retryGates({ ...base, status: 'IN_DOUBT' });
    const refused = gates.find((g) => !g.ok)!;
    expect(refused.hard).toBe(true);
    expect(refused.detail).toContain('may or may not have moved');
  });
});
