import { z } from 'zod';
import type { MaturityInstructionBody } from '@/api/accumulation';

/** What happens at the end of the running term (spec D7): reinvest for a new term, or pay out. */
export const instructionSchema = z
  .object({
    action: z.enum(['REINVEST', 'PAY_OUT']),
    termMonths: z.string().trim(),
    payeeRef: z.string().trim().max(200),
  })
  .superRefine((v, ctx) => {
    if (v.action === 'REINVEST' && v.termMonths === '') {
      ctx.addIssue({ code: 'custom', path: ['termMonths'], message: 'Choose the term to reinvest for' });
    }
  });
export type InstructionValues = z.infer<typeof instructionSchema>;

/** A pay-out with no payee typed pays to the number the deposit came from, so none is sent. */
export function toInstructionBody(v: InstructionValues): MaturityInstructionBody {
  return v.action === 'REINVEST'
    ? { action: 'REINVEST', termMonths: Number(v.termMonths) }
    : { action: 'PAY_OUT', ...(v.payeeRef !== '' && { payeeRef: v.payeeRef }) };
}

export const payeeSchema = z.object({ payeeRef: z.string().trim().min(1, 'Who should be paid?').max(200) });
export type PayeeValues = z.infer<typeof payeeSchema>;
