import { z } from 'zod';
import type { ElectionKey } from '@/api/types';

const MODELS = ['GMM', 'VFA', 'PAA', 'IFRS9'] as const;

/**
 * The accounting policy elections and the values each permits -- ElectionKey.java's list, so the form refuses
 * what the server would. MODEL_OVERRIDE_ALLOWED takes NONE or a comma-separated list of models.
 */
export const ELECTION_VALUES: Record<ElectionKey, readonly string[]> = {
  MEASUREMENT_MODEL: MODELS,
  INVESTMENT_COMPONENT_RULE: [
    'NONE',
    'PREMIUMS_RETURNED',
    'SURRENDER_VALUE',
    'SURRENDER_VALUE_WITH_BONUSES',
    'FUND_VALUE',
    'WHOLE_BALANCE',
  ],
  MODEL_OVERRIDE_ALLOWED: ['NONE', ...MODELS],
  POLICY_LOANS: ['INSIDE_CONTRACT', 'OUTSIDE_CONTRACT'],
  ACQUISITION_CASH_FLOWS: ['SPREAD', 'EXPENSE_WHEN_INCURRED'],
  OCI_OPTION: ['OFF', 'ON'],
  RIDERS: ['HOST_GROUP', 'SEPARATE'],
  PREMIUM_BILLING: ['ACCRUAL_AT_INVOICE'],
  CONTRACT_RECOGNITION: ['ISSUE_DATE'],
  COHORT: ['ANNUAL'],
};

export const ELECTION_KEYS = Object.keys(ELECTION_VALUES) as ElectionKey[];

/** ElectionKey.permits, in TypeScript. */
export function permits(key: string, value: string): boolean {
  const allowed = ELECTION_VALUES[key as ElectionKey];
  if (!allowed || value.trim() === '') return false;
  if (key === 'MODEL_OVERRIDE_ALLOWED') {
    return value === 'NONE' || value.split(',').every((m) => (MODELS as readonly string[]).includes(m.trim()));
  }
  return allowed.includes(value);
}

/** Proposing an election (IFRS 17 spec §3). A factory because "today" is the server's civil date. Mirrors PolicyRegister.propose. */
export function electionSchema(today: string) {
  return z
    .object({
      key: z.string().min(1, 'Choose the election'),
      scope: z.string().trim(),
      value: z.string().trim(),
      effectiveFrom: z.string().trim(),
      rationale: z.string().trim().max(2000),
    })
    .superRefine((v, ctx) => {
      if (v.value === '') {
        ctx.addIssue({ code: 'custom', path: ['value'], message: 'Choose the value this election takes' });
      } else if (v.key !== '' && !permits(v.key, v.value)) {
        ctx.addIssue({ code: 'custom', path: ['value'], message: `${v.value} is not a permitted value for ${v.key}` });
      }
      if (v.effectiveFrom === '') {
        ctx.addIssue({ code: 'custom', path: ['effectiveFrom'], message: 'An election has an effective date' });
      } else if (v.effectiveFrom < today) {
        ctx.addIssue({
          code: 'custom',
          path: ['effectiveFrom'],
          message: 'An election applies from today or later; a past change is a restatement, made through journals',
        });
      }
    });
}
export type ElectionValues = z.infer<ReturnType<typeof electionSchema>>;

export const approvalSchema = z.object({
  signOffRef: z.string().trim().min(1, 'An approval names its sign-off reference').max(200),
});
export type ApprovalValues = z.infer<typeof approvalSchema>;

export const rejectionSchema = z.object({
  reason: z.string().trim().min(1, 'A rejection gives its reason').max(2000),
});
export type RejectionValues = z.infer<typeof rejectionSchema>;

export const reopenSchema = z.object({
  reason: z.string().trim().min(1, 'Reopening a locked period needs a reason').max(2000),
});
export type ReopenValues = z.infer<typeof reopenSchema>;
