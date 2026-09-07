import { z } from 'zod';
import type { CreateAccountRequest } from '@/api/types';

/** 4 digits, leading block 1=ASSET, 2=LIABILITY, 3=EQUITY, 4=INCOME, 5=EXPENSE
 *  -- mirrors `CreateAccountRequestDto`'s own `^[1-5]\d{3}$` pattern exactly. */
export const ACCOUNT_CODE_PATTERN = /^[1-5]\d{3}$/;

/** Zod schema for creating a chart-of-account row, mirroring `CreateAccountRequest`
 *  exactly -- `accountType`/`normalBalance` are never collected here, since both
 *  are derived server-side from `accountCode`'s own leading digit, and neither is
 *  `level`, which the server derives from the parent chain. */
export const createAccountFormSchema = z.object({
  accountCode: z
    .string()
    .trim()
    .regex(ACCOUNT_CODE_PATTERN, 'Must be 4 digits starting with 1-5 (e.g. 1000)'),
  // Optional: empty means "create a block root". A supplied value must still look like
  // an account code; whether it EXISTS, and whether accountCode sits inside its block,
  // are server-side questions this form cannot answer.
  parentCode: z
    .string()
    .trim()
    .refine((v) => v === '' || ACCOUNT_CODE_PATTERN.test(v), {
      message: 'Must be 4 digits starting with 1-5, or left blank',
    }),
  name: z.string().trim().min(1, 'Name is required').max(200, 'Must be 200 characters or fewer'),
  description: z.string().trim().max(2000, 'Must be 2000 characters or fewer'),
});

export type CreateAccountFormValues = z.infer<typeof createAccountFormSchema>;

export function blankCreateAccountForm(): CreateAccountFormValues {
  return { accountCode: '', parentCode: '', name: '', description: '' };
}

/**
 * An omitted parent and an omitted description are sent as ABSENT, not as empty
 * strings: `parentCode: ''` would fail the server's `^[1-5]\d{3}$` pattern with a 400
 * rather than creating the block root the user asked for, and `description: ''` would
 * store an empty string where null is meant.
 */
export function toApiRequest(values: CreateAccountFormValues): CreateAccountRequest {
  const parentCode = values.parentCode.trim();
  const description = values.description.trim();
  return {
    accountCode: values.accountCode.trim(),
    name: values.name.trim(),
    ...(parentCode === '' ? {} : { parentCode }),
    ...(description === '' ? {} : { description }),
  };
}
