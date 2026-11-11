import { z } from 'zod';

/**
 * Cash a customer handed the agent (2026-11-11). The server refuses a policy outside the agent's book; this only keeps
 * the form from sending what is plainly not a policy number or an amount.
 */
export const recordCashSchema = z.object({
  policyNumber: z.string().trim().min(1, 'Which policy is the cash for?'),
  amount: z
    .string()
    .trim()
    .regex(/^\d+(\.\d{1,2})?$/, 'An amount like 50000')
    .refine((v) => Number(v) > 0, 'An amount above zero'),
});
export type RecordCashValues = z.infer<typeof recordCashSchema>;

/** "I've banked this": the slip or mobile-money reference the agent paid the cash in under. */
export const bankedSchema = z.object({
  reference: z.string().trim().min(1, 'Give the bank slip or mobile-money reference').max(100),
});
export type BankedValues = z.infer<typeof bankedSchema>;
