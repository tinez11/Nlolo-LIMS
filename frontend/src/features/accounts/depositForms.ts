import { z } from 'zod';
import type { MaturityInstructionBody } from '@/api/accumulation';
import { payoutRouteFields, refineRoute, routeBody } from '@/features/payments/payoutRoute';

/** What happens at the end of the running term (spec D7): reinvest for a new term, or pay out. */
export const instructionSchema = z
  .object({
    action: z.enum(['REINVEST', 'PAY_OUT']),
    termMonths: z.string().trim(),
    ...payoutRouteFields,
  })
  .superRefine((v, ctx) => {
    if (v.action === 'REINVEST' && v.termMonths === '') {
      ctx.addIssue({ code: 'custom', path: ['termMonths'], message: 'Choose the term to reinvest for' });
    }
    // A blank mobile-money number is the number the deposit came from; a transfer or cheque names its payee.
    if (v.action === 'PAY_OUT' && v.method !== 'MOBILE_MONEY') refineRoute(v, ctx);
  });
export type InstructionValues = z.infer<typeof instructionSchema>;

/** A mobile-money pay-out with no number typed pays to the number the deposit came from, so no route is sent. */
export function toInstructionBody(v: InstructionValues): MaturityInstructionBody {
  if (v.action === 'REINVEST') return { action: 'REINVEST', termMonths: Number(v.termMonths) };
  if (v.method === 'MOBILE_MONEY' && v.payeeRef.trim() === '') return { action: 'PAY_OUT' };
  return { action: 'PAY_OUT', ...routeBody(v) };
}
