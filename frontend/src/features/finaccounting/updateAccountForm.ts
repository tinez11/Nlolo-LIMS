import { z } from 'zod';
import type { UpdateAccountRequest } from '@/api/types';

/** Zod schema for editing a chart-of-account row, mirroring `UpdateAccountRequest`
 *  exactly -- `accountCode`/`accountType`/`normalBalance` are not editable, and neither
 *  are `parentCode`/`level`: moving an account is a separate, unbuilt concern. */
export const updateAccountFormSchema = z.object({
  name: z.string().trim().min(1, 'Name is required').max(200, 'Must be 200 characters or fewer'),
  description: z.string().trim().max(2000, 'Must be 2000 characters or fewer'),
});

export type UpdateAccountFormValues = z.infer<typeof updateAccountFormSchema>;

export function blankUpdateAccountForm(
  currentName: string,
  currentDescription?: string | null,
): UpdateAccountFormValues {
  return { name: currentName, description: currentDescription ?? '' };
}

/** A cleared description is sent as absent rather than as an empty string, matching
 *  `createAccountForm`'s own treatment of the same field. */
export function toApiRequest(values: UpdateAccountFormValues): UpdateAccountRequest {
  const description = values.description.trim();
  return { name: values.name.trim(), ...(description === '' ? {} : { description }) };
}
