import { z } from 'zod';
import type { RenameAccountRequest } from '@/api/types';

/** Zod schema for renaming a chart-of-account row, mirroring `RenameAccountRequest`
 *  exactly -- `accountCode`/`accountType`/`normalBalance` are not editable, only `name`. */
export const renameAccountFormSchema = z.object({
  name: z.string().trim().min(1, 'Name is required').max(200, 'Must be 200 characters or fewer'),
});

export type RenameAccountFormValues = z.infer<typeof renameAccountFormSchema>;

export function blankRenameAccountForm(currentName: string): RenameAccountFormValues {
  return { name: currentName };
}

export function toApiRequest(values: RenameAccountFormValues): RenameAccountRequest {
  return { name: values.name.trim() };
}
