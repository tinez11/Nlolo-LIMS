import { describe, expect, it } from 'vitest';
import type { ApprovalRow } from '@/api/approvals';
import { approvalLink } from './approvalLinks';

const row = (over: Partial<ApprovalRow> = {}): ApprovalRow => ({
  reference: 'ref-1',
  policyNumber: 'POL-1',
  summary: 'something',
  yours: false,
  ...over,
});

describe('approvalLink', () => {
  it('sends an account withdrawal to its policy, on the account tab', () => {
    expect(approvalLink('account-withdrawal', row())).toBe('/staff/policies/POL-1?tab=account');
  });

  it('sends a payout to its own page', () => {
    expect(approvalLink('payout-approval', row({ reference: 'inst-9' }))).toBe('/staff/payouts/inst-9');
  });

  it('sends a declaration to the product it declares for', () => {
    expect(approvalLink('rate-declaration', row({ reference: 'prod-7', policyNumber: null }))).toBe(
      '/staff/products/prod-7',
    );
  });

  it('has no link for a policy kind that arrived without its policy', () => {
    expect(approvalLink('surrender', row({ policyNumber: null }))).toBeNull();
  });

  it('lists a kind it does not know, unlinked, rather than guessing', () => {
    expect(approvalLink('something-new', row())).toBeNull();
  });
});
