import { describe, expect, it } from 'vitest';
import { blankPayoutReview, needsProofOfLife, payoutReviewSchema } from './payoutReviewForm';

describe('payoutReviewSchema', () => {
  it('requires a payee reference', () => {
    const result = payoutReviewSchema(false).safeParse({ payeeRef: '', proofOfLifeMethod: '' });
    expect(result.success).toBe(false);
    expect(result.error?.issues[0]?.message).toBe('A payout needs a payee reference');
  });

  it('treats whitespace as no payee at all', () => {
    expect(payoutReviewSchema(false).safeParse({ payeeRef: '   ', proofOfLifeMethod: '' }).success).toBe(false);
  });

  it('requires a method when the payout is paid to a living person', () => {
    const result = payoutReviewSchema(true).safeParse({
      payeeRef: '+255700000009',
      proofOfLifeMethod: '',
    });
    expect(result.success).toBe(false);
    expect(result.error?.issues[0]?.path).toEqual(['proofOfLifeMethod']);
    expect(result.error?.issues[0]?.message).toBe('Proof that the life assured is alive is required');
  });

  it('accepts a survival review once a method is chosen', () => {
    expect(
      payoutReviewSchema(true).safeParse({ payeeRef: '+255700000009', proofOfLifeMethod: 'IN_PERSON' }).success,
    ).toBe(true);
  });

  it('asks nothing of a maturity, which is owed by the calendar alone', () => {
    expect(
      payoutReviewSchema(false).safeParse({ payeeRef: '+255700000009', proofOfLifeMethod: '' }).success,
    ).toBe(true);
  });

  it('starts blank, and a blank form is not submittable either way', () => {
    expect(payoutReviewSchema(false).safeParse(blankPayoutReview()).success).toBe(false);
    expect(payoutReviewSchema(true).safeParse(blankPayoutReview()).success).toBe(false);
  });
});

describe('needsProofOfLife', () => {
  it('follows the server: an annuity redirected to beneficiaries owes none', () => {
    expect(needsProofOfLife({ kind: 'ANNUITY', proofOfLifeRequired: true })).toBe(true);
    expect(needsProofOfLife({ kind: 'ANNUITY', proofOfLifeRequired: false })).toBe(false);
    expect(needsProofOfLife({ kind: 'MATURITY', proofOfLifeRequired: false })).toBe(false);
  });
  it('falls back to the kind when the field is absent', () => {
    expect(needsProofOfLife({ kind: 'ANNUITY' })).toBe(true);
    expect(needsProofOfLife({ kind: 'RETURN_OF_PREMIUM' })).toBe(false);
  });
});
