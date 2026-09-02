import { z } from 'zod';
import type { OriginateLoanRequest } from '@/api/types';

/**
 * Zod schema for `POST /policies/{n}/loans`, mirroring `OriginateLoanRequestDto`:
 * a `Money` amount plus the `payeeRef` the disbursement is paid to.
 *
 * The amount is kept as a STRING all the way to the wire, never parsed to a
 * number. `Money.amount` is a decimal string by contract ("never a binary
 * float"), and the backend reads it straight into a `BigDecimal` -- routing it
 * through a JS number would introduce exactly the binary-float error the
 * contract exists to prevent.
 *
 * Two decimal places maximum, because `policyloan.policy_loan.principal_amount`
 * is `NUMERIC(19,2)`: a third decimal would be silently rounded by Postgres, so
 * it is rejected here where the user can still see and fix it.
 *
 * The amount ceiling -- available loan value -- is deliberately NOT checked
 * here. It is cash value net of existing encumbrance and any live reservation,
 * none of which this form can see, and only the server can evaluate it without
 * a race. An over-limit request comes back as a 409 whose message names the
 * actual available value, which is more useful than a guess.
 */
export const originateLoanFormSchema = z.object({
  amount: z
    .string()
    .trim()
    .min(1, 'A loan amount is required')
    .regex(/^\d+(\.\d{1,2})?$/, 'Enter an amount in shillings, up to 2 decimal places')
    .refine((value) => Number(value) > 0, 'A loan must be for more than zero'),
  payeeRef: z.string().trim().min(1, 'A payee reference is required'),
});

export type OriginateLoanFormValues = z.infer<typeof originateLoanFormSchema>;

export function blankOriginateLoanForm(): OriginateLoanFormValues {
  return { amount: '', payeeRef: '' };
}

export function toApiRequest(values: OriginateLoanFormValues): OriginateLoanRequest {
  return {
    // TZS is the platform's only currency: every money column defaults to it and
    // nothing converts. Hard-coded rather than offered as a choice the backend
    // would then have to reject.
    requestedAmount: { amount: values.amount.trim(), currencyCode: 'TZS' },
    payeeRef: values.payeeRef.trim(),
  };
}
