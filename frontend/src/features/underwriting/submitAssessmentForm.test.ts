import { describe, expect, it } from 'vitest';
import { submitAssessmentFormSchema, toApiRequest } from './submitAssessmentForm';

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
