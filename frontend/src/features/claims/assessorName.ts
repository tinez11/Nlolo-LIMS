import type { ClaimAssessmentView } from '@/api/types';

/**
 * How to name an assessor to another person.
 *
 * `assessor` is the identity-provider subject -- a uuid, and the thing separation of duties
 * compares. It is never shown: "recommended by 1697c88f-78d8-40e2-ae75-1bad3d6180ca" tells a
 * claims manager nothing about whose judgement they are being asked to trust.
 *
 * `assessorName` is captured from the assessor's token when they write the assessment. Rows
 * recorded before that have none, and there is nothing to recover it from, so they say so --
 * `fallback` is phrased by the caller because the sentence around it differs.
 */
export function assessorName(
  assessment: Pick<ClaimAssessmentView, 'assessorName'>,
  fallback = 'an assessor whose name was not recorded',
): string {
  const name = assessment.assessorName?.trim();
  return name ? name : fallback;
}
