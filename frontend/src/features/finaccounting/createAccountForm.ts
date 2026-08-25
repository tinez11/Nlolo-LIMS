import { z } from 'zod';
import type { CreateAccountRequest } from '@/api/types';

/** 4 digits, leading block 1=ASSET, 2=LIABILITY, 3=EQUITY, 4=INCOME, 5=EXPENSE
 *  -- mirrors `CreateAccountRequestDto`'s own `^[1-5]\d{3}$` pattern exactly. */
export const ACCOUNT_CODE_PATTERN = /^[1-5]\d{3}$/;

/** Zod schema for creating a chart-of-account row, mirroring `CreateAccountRequest`
 *  exactly -- `accountType`/`normalBalance` are never collected here, since both
 *  are derived server-side from `accountCode`'s own leading digit. */
export const createAccountFormSchema = z.object({
  accountCode: z
    .string()
    .trim()
    .regex(ACCOUNT_CODE_PATTERN, 'Must be 4 digits starting with 1-5 (e.g. 1000)'),
  name: z.string().trim().min(1, 'Name is required').max(200, 'Must be 200 characters or fewer'),
});

export type CreateAccountFormValues = z.infer<typeof createAccountFormSchema>;

export function blankCreateAccountForm(): CreateAccountFormValues {
  return { accountCode: '', name: '' };
}

export function toApiRequest(values: CreateAccountFormValues): CreateAccountRequest {
  return { accountCode: values.accountCode.trim(), name: values.name.trim() };
}
