import { z } from 'zod';
import type { SubmitAssessmentRequest } from '@/api/types';

/**
 * Zod schema for `POST /underwriting/cases/{caseId}/assessments`, mirroring
 * `SubmitAssessmentRequest` exactly: `assessmentType` and `findings` are
 * required, `riskScore` is genuinely optional (no `@NotNull`, no bounds
 * annotated on the Java DTO -- `SimpleRulesEngine`'s 0-100 thresholds are its
 * own semantic assumption, not a validated constraint).
 */

const RISK_SCORE_PATTERN = /^\d+(\.\d+)?$/;

export const submitAssessmentFormSchema = z.object({
  assessmentType: z.enum(['MEDICAL', 'FINANCIAL', 'OCCUPATIONAL']),
  findings: z.string().trim().min(1, 'Findings are required'),
  riskScore: z
    .string()
    .trim()
    .refine((v) => v === '' || RISK_SCORE_PATTERN.test(v), 'Must be a positive number'),
});

export type SubmitAssessmentFormValues = z.infer<typeof submitAssessmentFormSchema>;

export function blankSubmitAssessmentForm(): SubmitAssessmentFormValues {
  return { assessmentType: 'MEDICAL', findings: '', riskScore: '' };
}

export function toApiRequest(values: SubmitAssessmentFormValues): SubmitAssessmentRequest {
  return {
    assessmentType: values.assessmentType,
    findings: values.findings.trim(),
    riskScore: values.riskScore === '' ? null : Number(values.riskScore),
  };
}
