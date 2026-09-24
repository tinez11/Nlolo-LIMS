import { describe, expect, it } from 'vitest';
import {
  blankApproveDecision,
  blankRejectDecision,
  settlementDecisionFormSchema,
  settlementDecisionSchema,
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

describe('the ceiling', () => {
  const cover = { amount: '800000.00', currencyCode: 'TZS' };

  it('refuses more than the claim is covered for', () => {
    // The exact case that produced "Approved amount 1500000 exceeds the 800000.00
    // this claim is covered for" -- a 422 arriving after a confirmation step, on a
    // figure taken from a placeholder.
    const result = settlementDecisionSchema(cover).safeParse(validApprove());
    expect(result.success).toBe(false);
  });

  it('names the limit in money rather than repeating the server sentence', () => {
    const result = settlementDecisionSchema(cover).safeParse(validApprove());
    expect(result.success).toBe(false);
    if (result.success) return;
    expect(result.error.issues[0]?.message).toContain('TZS 800,000.00');
  });

  it('allows exactly the cover -- the bound is INCLUSIVE', () => {
    // Claim.approve uses `> ceiling`, not `>=`: a death claim normally pays the
    // whole of the cover, so an exclusive bound would refuse the commonest correct
    // settlement on the platform.
    const result = settlementDecisionSchema(cover).safeParse({
      ...validApprove(),
      approvedAmount: '800000.00',
    });
    expect(result.success).toBe(true);
  });

  it('allows less than the cover -- a partial settlement is a decision, not an error', () => {
    const result = settlementDecisionSchema(cover).safeParse({
      ...validApprove(),
      approvedAmount: '500000.00',
    });
    expect(result.success).toBe(true);
  });

  it('checks nothing extra when the cover is unknown, leaving the server the authority', () => {
    // Null means the cover has not loaded yet, or its read failed (a 409 when the
    // policy changed after registration). Either way the form cannot know the
    // ceiling, so it must not invent one -- the server checks on submission.
    expect(settlementDecisionSchema(null).safeParse(validApprove()).success).toBe(true);
  });

  it('does not bound a REJECTION, which pays nothing', () => {
    expect(settlementDecisionSchema(cover).safeParse(validReject()).success).toBe(true);
  });
});

describe('the starting amount', () => {
  const cover = { amount: '800000.00', currencyCode: 'TZS' };
  const recommended = { amount: '650000.00', currencyCode: 'TZS' };

  it('is blank when nothing is known, exactly as before', () => {
    expect(blankApproveDecision().approvedAmount).toBe('');
  });

  it('prefers the assessor recommendation over the cover', () => {
    // The business rule, not a tie-break. A human read the evidence and wrote
    // 650,000; a manager who silently pays the full 800,000 has overruled them
    // without noticing they did.
    const values = blankApproveDecision(recommended, cover);
    expect(values.approvedAmount).toBe('650000.00');
  });

  it('falls back to the cover when there is no assessment', () => {
    // MATURITY approves straight from REGISTERED with no assessment at all.
    expect(blankApproveDecision(null, cover).approvedAmount).toBe('800000.00');
  });

  it('carries the currency of whichever figure it started from', () => {
    expect(blankApproveDecision(null, { amount: '5.00', currencyCode: 'USD' }).approvedCurrency)
      .toBe('USD');
  });

  it('leaves the payee reference empty -- nothing can guess where money should go', () => {
    expect(blankApproveDecision(recommended, cover).payeeRef).toBe('');
  });

  it('produces a value the bounded schema accepts', () => {
    // The prefill and the validation must agree. If the default were ever above the
    // ceiling the form would open already invalid.
    const values = blankApproveDecision(null, cover);
    const result = settlementDecisionSchema(cover).safeParse({
      ...values,
      payeeRef: 'MOBILE-MONEY-REF-1',
    });
    expect(result.success).toBe(true);
  });
});
