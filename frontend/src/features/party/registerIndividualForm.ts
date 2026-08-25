import { z } from 'zod';
import type { RegisterIndividualRequest } from '@/api/types';
import { todayIso } from '@/lib/dates';
import { ISO_DATE_PATTERN, PHONE_PATTERN } from '@/lib/patterns';

/**
 * Zod schema for `POST /parties/individuals`, mirroring `ContactInfo`'s real
 * constraints (`ContactInfo.java`): both phone and email are OPTIONAL --
 * Jakarta's `@Pattern`/`@Email` treat null as valid -- but must match their
 * format when present. `dateOfBirth` has no backend upper-bound check beyond
 * "not in the future", so that is the only date rule mirrored here.
 */
export const registerIndividualFormSchema = z.object({
  fullName: z.string().trim().min(1, 'Full name is required'),
  dateOfBirth: z
    .string()
    .trim()
    .min(1, 'Date of birth is required')
    .regex(ISO_DATE_PATTERN, 'Not a valid date')
    .refine((v) => !ISO_DATE_PATTERN.test(v) || v <= todayIso(), 'Cannot be in the future'),
  phoneNumber: z
    .string()
    .trim()
    .refine((v) => v === '' || PHONE_PATTERN.test(v), 'Must match +255XXXXXXXXX'),
  email: z
    .string()
    .trim()
    .refine((v) => v === '' || z.string().email().safeParse(v).success, 'Not a valid email'),
});

export type RegisterIndividualFormValues = z.infer<typeof registerIndividualFormSchema>;

export function blankRegisterIndividualForm(): RegisterIndividualFormValues {
  return { fullName: '', dateOfBirth: '', phoneNumber: '', email: '' };
}

export function toApiRequest(values: RegisterIndividualFormValues): RegisterIndividualRequest {
  const phoneNumber = values.phoneNumber.trim();
  const email = values.email.trim();
  return {
    fullName: values.fullName.trim(),
    dateOfBirth: values.dateOfBirth,
    contactInfo: {
      ...(phoneNumber && { phoneNumber }),
      ...(email && { email }),
    },
  };
}
