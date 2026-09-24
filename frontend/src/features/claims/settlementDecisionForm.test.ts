import { describe, expect, it } from 'vitest';
import {
  blankApproveDecision,
  blankRejectDecision,
  recommendationExceedsCover,
  settlementDecisionFormSchema,
  settlementDecisionSchema,
  switchDecision,
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

  it('starts at the cover when the recommendation exceeds it', () => {
    // Seen in the console: a TZS 50,000,000 recommendation on a claim covered for
    // 20,000,000 opened the approval form at 50,000,000 -- a value it then refused.
    const overCover = { amount: '50000000.00', currencyCode: 'TZS' };
    const ceiling = { amount: '20000000.00', currencyCode: 'TZS' };
    const values = blankApproveDecision(overCover, ceiling);
    expect(values.approvedAmount).toBe('20000000.00');
    expect(
      settlementDecisionSchema(ceiling).safeParse({ ...values, payeeRef: 'REF-1' }).success,
    ).toBe(true);
  });

  it('keeps a recommendation equal to the cover -- the bound is inclusive', () => {
    expect(blankApproveDecision(cover, cover).approvedAmount).toBe('800000.00');
  });

  it('keeps the recommendation when the cover is unknown, claiming nothing about a ceiling', () => {
    expect(blankApproveDecision(recommended, null).approvedAmount).toBe('650000.00');
  });
});

describe('switchDecision', () => {
  const cover = { amount: '800000.00', currencyCode: 'TZS' };
  const recommended = { amount: '650000.00', currencyCode: 'TZS' };

  it('brings the prefilled amount back on Reject -> Approve', () => {
    // It used to come back blank, and nothing refilled it.
    const next = switchDecision('approve', false, recommended, cover);
    expect(next).toMatchObject({ approved: true, approvedAmount: '650000.00', approvedCurrency: 'TZS' });
  });

  it('falls back to the cover when there is no recommendation', () => {
    expect(switchDecision('approve', false, null, cover)).toMatchObject({ approvedAmount: '800000.00' });
  });

  it('changes nothing when the selected branch is chosen again', () => {
    // Clicking "Approve" while approving used to wipe the amount and the payee reference.
    expect(switchDecision('approve', true, recommended, cover)).toBeNull();
    expect(switchDecision('reject', false, recommended, cover)).toBeNull();
  });

  it('switches to a blank rejection', () => {
    expect(switchDecision('reject', true, recommended, cover)).toEqual({
      approved: false,
      rejectionReason: '',
    });
  });
});

describe('recommendationExceedsCover', () => {
  const cover = { amount: '800000.00', currencyCode: 'TZS' };

  it('is true only strictly above the cover', () => {
    expect(recommendationExceedsCover({ amount: '800000.01', currencyCode: 'TZS' }, cover)).toBe(true);
    expect(recommendationExceedsCover({ amount: '800000.00', currencyCode: 'TZS' }, cover)).toBe(false);
    expect(recommendationExceedsCover({ amount: '1.00', currencyCode: 'TZS' }, cover)).toBe(false);
  });

  it('is false when either figure is missing', () => {
    expect(recommendationExceedsCover(null, cover)).toBe(false);
    expect(recommendationExceedsCover(cover, null)).toBe(false);
  });
});
