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
    .refine((v) => v === '' || RISK_SCORE_PATTERN.test(v), 'Must be a positive number')
    // 0 to 100, the scale the engine's bands are on (2026-11-07; the server refuses outside it too).
    .refine((v) => v === '' || !RISK_SCORE_PATTERN.test(v) || Number(v) <= 100, 'A risk score is from 0 to 100'),
});

/**
 * The recommendation's bands, as `SimpleRulesEngine` cuts them (2026-11-07: the underwriter was shown none of this).
 * Placeholder values there, pending the actuary's own; change them in both places together.
 */
export const RISK_SCORE_BANDS = '0–39 accept · 40–74 extra premium · 75–89 decline · 90+ more evidence';

/**
 * What a score alone would recommend, in plain words, or null when there is no valid score. The loading is the
 * engine's: one percent per point above 40, never under 5%. Age, cover and occupation can still decline a case on
 * their own -- the case's recommendation, after submitting, is the whole answer.
 */
export function scoreRecommendation(raw: string): string | null {
  const v = raw.trim();
  if (v === '' || !RISK_SCORE_PATTERN.test(v)) return null;
  const score = Number(v);
  if (score > 100) return null;
  if (score >= 90) return 'Postpone for more evidence';
  if (score >= 75) return 'Decline';
  // Two decimals like the engine's loading, without trailing zeros: 52.5 is +12.5%, not a rounded +13%.
  if (score >= 40) return `Accept with +${Number(Math.max(5, score - 40).toFixed(2))}% premium`;
  return 'Accept at the standard price';
}

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
