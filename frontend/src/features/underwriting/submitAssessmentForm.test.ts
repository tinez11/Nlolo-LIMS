import { describe, expect, it } from 'vitest';
import { scoreRecommendation, submitAssessmentFormSchema, toApiRequest } from './submitAssessmentForm';

const valid = () => ({
  assessmentType: 'MEDICAL' as const,
  findings: 'Standard risk, no adverse findings',
  riskScore: '10',
});

describe('submitAssessmentFormSchema', () => {
  it('accepts a well-formed request', () => {
    expect(submitAssessmentFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('accepts every assessment type the spec declares', () => {
    for (const assessmentType of ['MEDICAL', 'FINANCIAL', 'OCCUPATIONAL'] as const) {
      expect(submitAssessmentFormSchema.safeParse({ ...valid(), assessmentType }).success).toBe(
        true,
      );
    }
  });

  it('accepts a blank risk score -- genuinely optional server-side', () => {
    expect(submitAssessmentFormSchema.safeParse({ ...valid(), riskScore: '' }).success).toBe(true);
  });

  it('accepts a decimal risk score', () => {
    expect(submitAssessmentFormSchema.safeParse({ ...valid(), riskScore: '42.5' }).success).toBe(
      true,
    );
  });

  it('rejects blank findings', () => {
    expect(submitAssessmentFormSchema.safeParse({ ...valid(), findings: '' }).success).toBe(false);
  });

  it('rejects a non-numeric risk score', () => {
    expect(submitAssessmentFormSchema.safeParse({ ...valid(), riskScore: 'high' }).success).toBe(
      false,
    );
  });

  it('rejects a negative risk score', () => {
    expect(submitAssessmentFormSchema.safeParse({ ...valid(), riskScore: '-5' }).success).toBe(
      false,
    );
  });

  // 2026-11-07: the scale is 0 to 100 (the engine's bands), and 500 used to be accepted.
  it('keeps the score on its 0 to 100 scale', () => {
    expect(submitAssessmentFormSchema.safeParse({ ...valid(), riskScore: '100' }).success).toBe(true);
    const over = submitAssessmentFormSchema.safeParse({ ...valid(), riskScore: '101' });
    expect(over.success).toBe(false);
    expect(over.error?.issues[0]?.message).toBe('A risk score is from 0 to 100');
  });
});

describe('scoreRecommendation', () => {
  it('says what each band recommends, in the engine’s own cut-offs', () => {
    expect(scoreRecommendation('')).toBeNull();
    expect(scoreRecommendation('abc')).toBeNull();
    expect(scoreRecommendation('0')).toBe('Accept at the standard price');
    expect(scoreRecommendation('39.9')).toBe('Accept at the standard price');
    expect(scoreRecommendation('40')).toBe('Accept with +5% premium'); // the 5% floor
    expect(scoreRecommendation('60')).toBe('Accept with +20% premium');
    expect(scoreRecommendation('52.5')).toBe('Accept with +12.5% premium');
    expect(scoreRecommendation('75')).toBe('Decline');
    expect(scoreRecommendation('90')).toBe('Postpone for more evidence');
    expect(scoreRecommendation('101')).toBeNull();
  });
});

describe('toApiRequest', () => {
  it('sends a null riskScore when left blank, not an empty string', () => {
    const request = toApiRequest(submitAssessmentFormSchema.parse({ ...valid(), riskScore: '' }));
    expect(request.riskScore).toBeNull();
  });

  it('coerces a filled risk score to a number', () => {
    const request = toApiRequest(submitAssessmentFormSchema.parse(valid()));
    expect(request.riskScore).toBe(10);
  });

  it('trims findings', () => {
    const request = toApiRequest(
      submitAssessmentFormSchema.parse({ ...valid(), findings: '  padded  ' }),
    );
    expect(request.findings).toBe('padded');
  });
});
