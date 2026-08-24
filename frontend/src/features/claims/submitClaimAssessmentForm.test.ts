import { describe, expect, it } from 'vitest';
import { submitClaimAssessmentFormSchema, toApiRequest } from './submitClaimAssessmentForm';

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
