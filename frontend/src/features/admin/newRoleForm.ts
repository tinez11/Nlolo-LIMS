import { z } from 'zod';
import type { RoleDraft } from '@/api/access';

/**
 * Zod schema for `POST /access/roles` (2026-10-26), mirroring AccessAdministration.createRole: a code that starts with a
 * letter -- the server upper-cases it and turns anything else into underscores -- a name, and the permissions it carries.
 */
export const newRoleFormSchema = z.object({
  code: z.string().trim().regex(/^[A-Za-z][A-Za-z0-9_ ]*$/, 'Start with a letter; letters, digits and underscores, e.g. BRANCH_MANAGER'),
  name: z.string().trim().min(1, 'A name is required'),
  description: z.string(),
  permissions: z.array(z.string()),
});

export type NewRoleFormValues = z.infer<typeof newRoleFormSchema>;

export function blankNewRoleForm(): NewRoleFormValues {
  return { code: '', name: '', description: '', permissions: [] };
}

export function toApiRequest(values: NewRoleFormValues): RoleDraft {
  return {
    code: values.code.trim().toUpperCase().replace(/[^A-Z0-9]+/g, '_'),
    name: values.name.trim(),
    description: values.description.trim() || null,
    permissions: values.permissions,
  };
}
