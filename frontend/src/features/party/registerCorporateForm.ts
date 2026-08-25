import { z } from 'zod';
import type { RegisterCorporateRequest } from '@/api/types';
import { PHONE_PATTERN } from '@/lib/patterns';

/**
 * Zod schema for `POST /parties/corporates`. `registrationNumber` uniqueness
 * is real backend state (`ux_party_registration_number`-style constraint)
 * that no client-side rule can anticipate -- a duplicate genuinely 409s.
 */
export const registerCorporateFormSchema = z.object({
  registeredName: z.string().trim().min(1, 'Registered name is required'),
  registrationNumber: z.string().trim().min(1, 'Registration number is required'),
  phoneNumber: z
    .string()
    .trim()
    .refine((v) => v === '' || PHONE_PATTERN.test(v), 'Must match +255XXXXXXXXX'),
  email: z
    .string()
    .trim()
    .refine((v) => v === '' || z.string().email().safeParse(v).success, 'Not a valid email'),
});

export type RegisterCorporateFormValues = z.infer<typeof registerCorporateFormSchema>;

export function blankRegisterCorporateForm(): RegisterCorporateFormValues {
  return { registeredName: '', registrationNumber: '', phoneNumber: '', email: '' };
}

export function toApiRequest(values: RegisterCorporateFormValues): RegisterCorporateRequest {
  const phoneNumber = values.phoneNumber.trim();
  const email = values.email.trim();
  return {
    registeredName: values.registeredName.trim(),
    registrationNumber: values.registrationNumber.trim(),
    contactInfo: {
      ...(phoneNumber && { phoneNumber }),
      ...(email && { email }),
    },
  };
}
