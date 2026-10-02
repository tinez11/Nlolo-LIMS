import { describe, expect, it } from 'vitest';
import type { AccountView, AdjustmentView, RateDeclarationView, WithdrawalView } from '@/api/types';
import {
  approveRateGates,
  approveWithdrawalGates,
  decideAdjustmentGates,
  requestWithdrawalGates,
} from './accumulationGates';

const money = (amount: string) => ({ amount, currencyCode: 'TZS' });

const account: AccountView = {
  policyNumber: 'POL-1',
  status: 'OPEN',
  balance: money('190000.00'),
  openedOn: '2026-01-01',
  closedReason: null,
  closedOn: null,
  entries: [],
};

const withdrawal: WithdrawalView = {
  withdrawalId: 'w-1',
  policyNumber: 'POL-1',
  amount: money('40000.00'),
  payeeRef: '+255700000001',
  status: 'REQUESTED',
  requestedBy: 'staff-one',
  requestedAt: '2026-10-01T08:00:00Z',
  approvedBy: null,
  approvedAt: null,
};

const adjustment: AdjustmentView = {
  adjustmentId: 'a-1',
  policyNumber: 'POL-1',
  amount: '-250.00',
  reason: 'Fee charged twice in error',
  status: 'PROPOSED',
  proposedBy: 'finance-one',
  proposedAt: '2026-10-01T08:00:00Z',
  decidedBy: null,
  decidedAt: null,
};

const rate: RateDeclarationView = {
  declarationId: 'r-1',
  productId: 'p-1',
  ratePercent: '6.5',
  effectiveFrom: '2026-11-01',
  status: 'PROPOSED',
  proposedBy: 'admin-one',
  proposedAt: '2026-10-01T08:00:00Z',
  approvedBy: null,
  approvedAt: null,
};

describe('requestWithdrawalGates', () => {
  it('passes an open account with nothing in flight', () => {
    expect(requestWithdrawalGates(account, []).every((g) => g.ok)).toBe(true);
  });

  it('refuses a closed account, naming why it closed', () => {
    const gate = requestWithdrawalGates({ ...account, status: 'CLOSED', closedReason: 'EXHAUSTED' }, [])[0]!;
    expect(gate.ok).toBe(false);
    expect(gate.detail).toBe('The account is closed (EXHAUSTED).');
  });

  it('refuses a second withdrawal while one is in flight, in the server’s words', () => {
    const gate = requestWithdrawalGates(account, [{ ...withdrawal, status: 'APPROVED' }])[1]!;
    expect(gate.ok).toBe(false);
    expect(gate.detail).toBe('A withdrawal is already in flight on policy POL-1');
  });

  it('does not count a paid or failed withdrawal as in flight', () => {
    const settled = [{ ...withdrawal, status: 'PAID' as const }, { ...withdrawal, status: 'FAILED' as const }];
    expect(requestWithdrawalGates(account, settled)[1]!.ok).toBe(true);
  });
});

describe('approveWithdrawalGates', () => {
  it('refuses the requester, in the server’s words', () => {
    const gate = approveWithdrawalGates(withdrawal, 'staff-one')[1]!;
    expect(gate.ok).toBe(false);
    expect(gate.detail).toBe('A withdrawal must be approved by someone other than the person who requested it');
  });

  it('passes a second person', () => {
    expect(approveWithdrawalGates(withdrawal, 'finance-two').every((g) => g.ok)).toBe(true);
  });

  it('does not treat an unknown viewer as the requester', () => {
    expect(approveWithdrawalGates(withdrawal, undefined)[1]!.ok).toBe(true);
  });

  it('refuses a withdrawal that is no longer awaiting approval', () => {
    const gate = approveWithdrawalGates({ ...withdrawal, status: 'PAID' }, 'finance-two')[0]!;
    expect(gate.ok).toBe(false);
    expect(gate.detail).toBe('This withdrawal is paid.');
  });
});

describe('decideAdjustmentGates', () => {
  it('refuses the proposer, in the server’s words', () => {
    const gate = decideAdjustmentGates(adjustment, 'finance-one')[1]!;
    expect(gate.ok).toBe(false);
    expect(gate.detail).toBe('An adjustment must be decided by someone other than the person who proposed it');
  });

  it('passes a second person on a proposal', () => {
    expect(decideAdjustmentGates(adjustment, 'finance-two').every((g) => g.ok)).toBe(true);
  });
});

describe('approveRateGates', () => {
  it('refuses the proposer, in the server’s words', () => {
    const gate = approveRateGates(rate, 'admin-one')[1]!;
    expect(gate.ok).toBe(false);
    expect(gate.detail).toBe('A declared rate must be approved by someone other than the person who proposed it');
  });

  it('refuses an approved rate in the server’s words', () => {
    const gate = approveRateGates({ ...rate, status: 'APPROVED', approvedBy: 'finance-two' }, 'finance-three')[0]!;
    expect(gate.detail).toBe('This rate declaration is approved, not awaiting approval');
  });
});
