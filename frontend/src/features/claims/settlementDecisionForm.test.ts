import { describe, expect, it } from 'vitest';
import {
  blankApproveDecision,
  blankRejectDecision,
  settlementDecisionFormSchema,
  toApiRequest,
} from './settlementDecisionForm';

const validApprove = () => ({
  approved: true as const,
  approvedAmount: '1500000.00',
  approvedCurrency: 'TZS',
  payeeRef: 'MOBILE-MONEY-REF-1',
});

const validReject = () => ({
  approved: false as const,
  rejectionReason: 'Insufficient evidence',
});

describe('settlementDecisionFormSchema -- approve branch', () => {
  it('accepts a well-formed approval', () => {
    expect(settlementDecisionFormSchema.safeParse(validApprove()).success).toBe(true);
  });

  it('rejects a blank payee reference', () => {
    expect(
      settlementDecisionFormSchema.safeParse({ ...validApprove(), payeeRef: '' }).success,
    ).toBe(false);
  });

  it('rejects a malformed approved amount', () => {
    expect(
      settlementDecisionFormSchema.safeParse({ ...validApprove(), approvedAmount: 'lots' })
        .success,
    ).toBe(false);
  });

  it('rejects a zero approved amount', () => {
    expect(
      settlementDecisionFormSchema.safeParse({ ...validApprove(), approvedAmount: '0.00' })
        .success,
    ).toBe(false);
  });
});

describe('settlementDecisionFormSchema -- reject branch', () => {
  it('accepts a well-formed rejection', () => {
    expect(settlementDecisionFormSchema.safeParse(validReject()).success).toBe(true);
  });

  it('accepts a blank rejection reason -- genuinely optional server-side', () => {
    expect(
      settlementDecisionFormSchema.safeParse({ ...validReject(), rejectionReason: '' }).success,
    ).toBe(true);
  });

  it('does not require approvedAmount or payeeRef on this branch', () => {
    const result = settlementDecisionFormSchema.safeParse({ approved: false, rejectionReason: '' });
    expect(result.success).toBe(true);
  });
});

describe('toApiRequest', () => {
  it('nests approvedAmount as Money and sends a null rejectionReason on approval', () => {
    const request = toApiRequest(settlementDecisionFormSchema.parse(validApprove()));
    expect(request).toEqual({
      approved: true,
      approvedAmount: { amount: '1500000.00', currencyCode: 'TZS' },
      payeeRef: 'MOBILE-MONEY-REF-1',
      rejectionReason: null,
    });
  });

  it('omits approvedAmount and payeeRef on rejection', () => {
    const request = toApiRequest(settlementDecisionFormSchema.parse(validReject()));
    expect(request.approved).toBe(false);
    expect(request.approvedAmount).toBeUndefined();
    expect(request.payeeRef).toBeNull();
    expect(request.rejectionReason).toBe('Insufficient evidence');
  });

  it('sends null, not an empty string, for a blank rejection reason', () => {
    const request = toApiRequest(
      settlementDecisionFormSchema.parse({ approved: false, rejectionReason: '' }),
    );
    expect(request.rejectionReason).toBeNull();
  });
});

describe('blank form factories', () => {
  it('blankApproveDecision starts on the approve branch', () => {
    expect(blankApproveDecision().approved).toBe(true);
  });

  it('blankRejectDecision starts on the reject branch', () => {
    expect(blankRejectDecision().approved).toBe(false);
  });
});
