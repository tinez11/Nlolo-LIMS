import { describe, expect, it } from 'vitest';
import type { ClaimView, PolicyMemberView, PolicyView } from '@/api/types';
import { transferVerdict } from './transferVerdict';

const claim = (overrides: Partial<ClaimView> = {}) =>
  ({ claimId: 'c-1', status: 'SETTLEMENT_REQUESTED', policyNumber: 'GRP-1', ...overrides }) as ClaimView;
const creditLifePolicy = { policyNumber: 'GRP-1', productCategory: 'CREDIT_LIFE', policyholderPartyId: 'lender-1', status: 'ACTIVE' } as PolicyView;
const member = (overrides: Partial<PolicyMemberView> = {}) =>
  ({ policyMemberId: 'm-1', status: 'ACTIVE', openDeathClaimId: 'c-1', exitReason: null, leftOn: null, ...overrides }) as PolicyMemberView;
const lenderPayee = 'nlolo bank1 — policyholder of GRP-1';

describe('transferVerdict', () => {
  it('lets the one live claim on a borrower be paid, to the lender', () => {
    const verdict = transferVerdict({ claimId: 'c-1', payeeRef: lenderPayee, claim: claim(), policy: creditLifePolicy, member: member() });
    expect(verdict).toMatchObject({ payable: true, reasons: [], notes: [], lenderPartyId: 'lender-1' });
  });

  it('blocks a duplicate: another claim is the one in progress on this life', () => {
    // The three approved claims on one borrower, each with its own transfer.
    const verdict = transferVerdict({ claimId: 'c-2', payeeRef: lenderPayee, claim: claim({ claimId: 'c-2' }), policy: creditLifePolicy, member: member({ openDeathClaimId: 'c-1' }) });
    expect(verdict.payable).toBe(false);
    expect(verdict.reasons[0]).toMatch(/Another death claim \(c-1\)/);
  });

  it('blocks a borrower who has already left cover -- the death was paid', () => {
    const verdict = transferVerdict({ claimId: 'c-2', payeeRef: lenderPayee, claim: claim(), policy: creditLifePolicy,
      member: member({ status: 'EXITED', exitReason: 'CLAIM_SETTLED', leftOn: '2026-09-23', openDeathClaimId: null }) });
    expect(verdict.payable).toBe(false);
    expect(verdict.reasons[0]).toMatch(/already left cover \(Death claim paid/);
  });

  it('flags, without blocking, a payee typed at approval that is not the lender', () => {
    const verdict = transferVerdict({ claimId: 'c-1', payeeRef: 'mobile', claim: claim(), policy: creditLifePolicy, member: member() });
    expect(verdict.payable).toBe(true);
    expect(verdict.notes[0]).toMatch(/Recorded at approval as “mobile”\. The payee is the lender/);
  });

  it('blocks an individual policy already closed by a death', () => {
    const verdict = transferVerdict({ claimId: 'c-1', payeeRef: '0712', claim: claim(),
      policy: { policyNumber: 'POL-1', productCategory: 'TERM_LIFE', status: 'SURRENDERED' } as PolicyView, member: null });
    expect(verdict.payable).toBe(false);
  });

  it('asks nothing of a mobile-money payee on individual business', () => {
    const verdict = transferVerdict({ claimId: 'c-1', payeeRef: '0712', claim: claim(),
      policy: { policyNumber: 'POL-1', productCategory: 'TERM_LIFE', status: 'ACTIVE' } as PolicyView, member: null });
    expect(verdict).toMatchObject({ payable: true, notes: [], lenderPartyId: null });
  });
});
