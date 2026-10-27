import { z } from 'zod';
import type { NewStaffUser } from '@/api/access';
import { normalisePhone, PHONE_MESSAGE } from '@/lib/patterns';

/**
 * Zod schema for `POST /access/users` (2026-10-26), mirroring AccessAdministration.createUser: first name, last name and
 * login name required; email, phone and branch optional. The login name is lower-case, as Keycloak stores it and as
 * deploy/server/add-user.sh names logins (`asha.juma`).
 *
 * The email decides how the first password arrives -- with one, the identity provider's own set-your-password link;
 * without, a one-time password shown to the administrator once -- the portal invite's rule.
 */
export const newUserFormSchema = z.object({
  firstName: z.string().trim().min(1, 'First name is required'),
  lastName: z.string().trim().min(1, 'Last name is required'),
  username: z
    .string()
    .trim()
    .min(3, 'At least 3 characters')
    .regex(/^[a-z0-9][a-z0-9._-]*$/, 'Lower-case letters, digits, dots, dashes and underscores, e.g. asha.juma'),
  email: z.string().trim().refine((v) => v === '' || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v), 'Not a valid email address'),
  phone: z.string().trim().refine((v) => v === '' || normalisePhone(v) !== null, PHONE_MESSAGE),
  homeBranch: z.string(),
  roleCodes: z.array(z.string()),
});

export type NewUserFormValues = z.infer<typeof newUserFormSchema>;

export function blankNewUserForm(): NewUserFormValues {
  return { firstName: '', lastName: '', username: '', email: '', phone: '', homeBranch: '', roleCodes: [] };
}

/** add-user.sh's convention: first.last, lower-case, letters only. */
export function suggestedUsername(firstName: string, lastName: string): string {
  return [firstName, lastName].map((s) => s.trim().toLowerCase().replace(/[^a-z0-9]+/g, '')).filter(Boolean).join('.');
}

export function toApiRequest(values: NewUserFormValues): NewStaffUser {
  return {
    username: values.username.trim(),
    firstName: values.firstName.trim(),
    lastName: values.lastName.trim(),
    email: values.email.trim() || null,
    phone: values.phone.trim() ? normalisePhone(values.phone) : null,
    homeBranch: values.homeBranch || null,
    roleCodes: values.roleCodes,
    permissions: [],
  };
}
