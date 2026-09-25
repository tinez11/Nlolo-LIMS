import { describe, expect, it } from 'vitest';
import {
  blankSubmitClaimAssessmentForm,
  submitClaimAssessmentFormSchema,
  submitClaimAssessmentSchema,
  toApiRequest,
} from './submitClaimAssessmentForm';

const valid = () => ({
  findings: 'Standard risk, no adverse findings',
  recommendedAmount: '1500000.00',
  recommendedCurrency: 'TZS',
  fraudIndicator: false,
});

describe('submitClaimAssessmentFormSchema', () => {
  it('accepts a well-formed request', () => {
    expect(submitClaimAssessmentFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('accepts fraudIndicator true', () => {
    expect(
      submitClaimAssessmentFormSchema.safeParse({ ...valid(), fraudIndicator: true }).success,
    ).toBe(true);
  });

  it('rejects blank findings', () => {
    expect(submitClaimAssessmentFormSchema.safeParse({ ...valid(), findings: '' }).success).toBe(
      false,
    );
  });

  it('rejects a malformed recommended amount', () => {
    expect(
      submitClaimAssessmentFormSchema.safeParse({ ...valid(), recommendedAmount: '1,500,000' })
        .success,
    ).toBe(false);
  });

  it('rejects a lowercase currency code', () => {
    expect(
      submitClaimAssessmentFormSchema.safeParse({ ...valid(), recommendedCurrency: 'tzs' }).success,
    ).toBe(false);
  });
});

describe('toApiRequest', () => {
  it('nests recommendedAmount as a Money object, never a bare number', () => {
    const request = toApiRequest(submitClaimAssessmentFormSchema.parse(valid()));
    expect(request.recommendedAmount).toEqual({ amount: '1500000.00', currencyCode: 'TZS' });
  });

  it('does not itself reject the claim -- fraudIndicator is scrutiny-only', () => {
    const request = toApiRequest(
      submitClaimAssessmentFormSchema.parse({ ...valid(), fraudIndicator: true }),
    );
    expect(request.fraudIndicator).toBe(true);
    expect(request.findings).toBeTruthy();
  });
});

describe('the ceiling', () => {
  const cover = { amount: '800000.00', currencyCode: 'TZS' };

  /*
    This bound has NO server counterpart, unlike the settlement form's.

    Claim.approve bounds the APPROVED amount; nothing bounds a recommendation. So an
    assessor could record 1,500,000 against an 800,000 cover, and the refusal landed
    later on a different person -- a manager looking at a colleague's recommendation
    they could not act on, with nothing on screen saying why.
  */
  it('refuses a recommendation above the cover, which the backend would not', () => {
    expect(submitClaimAssessmentSchema(cover).safeParse(valid()).success).toBe(false);
  });

  it('allows exactly the cover -- a death claim on credit life pays the whole balance', () => {
    const result = submitClaimAssessmentSchema(cover).safeParse({
      ...valid(),
      recommendedAmount: '800000.00',
    });
    expect(result.success).toBe(true);
  });

  it('allows recommending less', () => {
    const result = submitClaimAssessmentSchema(cover).safeParse({
      ...valid(),
      recommendedAmount: '650000.00',
    });
    expect(result.success).toBe(true);
  });

  it('checks nothing extra when the cover is unknown', () => {
    expect(submitClaimAssessmentSchema(null).safeParse(valid()).success).toBe(true);
  });
});

describe('the starting amount', () => {
  it('is blank when the cover is unknown', () => {
    expect(blankSubmitClaimAssessmentForm().recommendedAmount).toBe('');
  });

  it('starts at the full cover, the correct opening position for a death claim', () => {
    const values = blankSubmitClaimAssessmentForm({ amount: '800000.00', currencyCode: 'TZS' });
    expect(values.recommendedAmount).toBe('800000.00');
    expect(values.recommendedCurrency).toBe('TZS');
  });

  it('leaves findings empty -- the one thing nothing can prefill', () => {
    expect(
      blankSubmitClaimAssessmentForm({ amount: '800000.00', currencyCode: 'TZS' }).findings,
    ).toBe('');
  });

  it('produces a value its own bounded schema accepts', () => {
    const cover = { amount: '800000.00', currencyCode: 'TZS' };
    const result = submitClaimAssessmentSchema(cover).safeParse({
      ...blankSubmitClaimAssessmentForm(cover),
      findings: 'Balance confirmed with the lender',
    });
    expect(result.success).toBe(true);
  });
});
