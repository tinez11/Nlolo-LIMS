import { describe, expect, it } from 'vitest';
import { grantAccess } from '@/test/access';
import { clientActions, policyActions } from './quickActions';

const labels = (actions: { label: string }[]) => actions.map((a) => a.label);

describe('clientActions', () => {
  it('offers a claims officer the claim, filled in with the person', () => {
    grantAccess('clients.view', 'claims.view', 'claims.register');
    const actions = clientActions({ partyId: 'p-1', kycStatus: 'VERIFIED', partyType: 'INDIVIDUAL' });
    expect(labels(actions)).toEqual(['Register a claim']);
    expect(actions[0]?.to).toBe('/staff/claims/new?claimant=p-1');
  });

  it('offers nothing to someone who may only look', () => {
    grantAccess('clients.view');
    expect(clientActions({ partyId: 'p-1' })).toEqual([]);
  });

  it('records a payment against the one policy a client holds, else opens their policies', () => {
    grantAccess('collections.manage');
    expect(clientActions({ partyId: 'p-1' }, 'POL-1')[0]?.to).toBe('/staff/receipts/new?policy=POL-1');
    expect(clientActions({ partyId: 'p-1' })[0]?.to).toBe('/staff/parties/p-1?tab=policies');
  });

  it('offers KYC only while it is pending, and the portal only to a person', () => {
    grantAccess('clients.kyc', 'clients.portal');
    expect(labels(clientActions({ partyId: 'p-1', kycStatus: 'PENDING', partyType: 'INDIVIDUAL' })))
      .toEqual(['Verify KYC', 'Invite to portal']);
    expect(clientActions({ partyId: 'p-1', kycStatus: 'VERIFIED', partyType: 'CORPORATE' })).toEqual([]);
  });
});

describe('policyActions', () => {
  it('fills the claim with the policy and its holder', () => {
    grantAccess('claims.register');
    expect(policyActions({ policyNumber: 'POL-1', policyholderPartyId: 'p-1', status: 'ACTIVE' }, [], false)[0]?.to)
      .toBe('/staff/claims/new?policy=POL-1&claimant=p-1');
  });

  it('offers a claim only where the server would take one', () => {
    grantAccess('claims.register');
    const claimOn = (status: string, productCategory = 'TERM_LIFE') =>
      labels(policyActions({ policyNumber: 'POL-1', status, productCategory }, [], false)).includes('Register a claim');
    // Never on cover, or closed.
    expect(claimOn('PROPOSED')).toBe(false);
    expect(claimOn('NOT_TAKEN_UP')).toBe(false);
    expect(claimOn('CANCELLED_FREE_LOOK')).toBe(false);
    expect(claimOn('MATURED')).toBe(false);
    // On cover, or ended after an event it still covers.
    expect(claimOn('ACTIVE')).toBe(true);
    expect(claimOn('LAPSED')).toBe(true);
    expect(claimOn('SURRENDERED')).toBe(true);
    // A scheme must be in force today.
    expect(claimOn('ACTIVE', 'GROUP_LIFE')).toBe(true);
    expect(claimOn('LAPSED', 'CREDIT_LIFE')).toBe(false);
  });

  it('names the lender as claimant on credit life, and nobody on a group scheme', () => {
    grantAccess('claims.register');
    const to = (productCategory: string) =>
      policyActions({ policyNumber: 'S-1', policyholderPartyId: 'p-9', status: 'ACTIVE', productCategory }, [], false)[0]?.to;
    expect(to('CREDIT_LIFE')).toBe('/staff/claims/new?policy=S-1&claimant=p-9');
    expect(to('GROUP_LIFE')).toBe('/staff/claims/new?policy=S-1');
  });

  it('offers a tab only where the policy has it', () => {
    grantAccess('accounts.transact', 'loans.manage', 'policies.service');
    expect(labels(policyActions({ policyNumber: 'POL-1' }, ['overview'], false))).toEqual([]);
    expect(labels(policyActions({ policyNumber: 'POL-1' }, ['account', 'loans', 'beneficiaries'], false)))
      .toEqual(['Change beneficiaries', 'Withdrawal', 'Loan']);
  });

  it('opens surrender and reinstatement at their panels, only where the status allows them', () => {
    grantAccess('policies.surrender', 'policies.status');
    expect(policyActions({ policyNumber: 'POL-1', status: 'LAPSED' }, [], true).map((a) => a.label))
      .toEqual(['Surrender', 'Reinstate']);
    expect(policyActions({ policyNumber: 'POL-1', status: 'LAPSED' }, [], true).map((a) => a.to))
      .toEqual(['/staff/policies/POL-1?focus=value', '/staff/policies/POL-1?focus=lifecycle']);
    expect(labels(policyActions({ policyNumber: 'POL-1', status: 'SUSPENDED' }, [], true))).toEqual(['Resume']);
    expect(policyActions({ policyNumber: 'POL-1', status: 'PROPOSED' }, [], true)).toEqual([]);
  });
});
