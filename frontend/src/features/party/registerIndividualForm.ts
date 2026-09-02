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
 *
 * Everything below `email` is the V2 person record, and all of it is optional
 * for the same reason the backend made it optional: registration happens in
 * front of a person who may not have the answers to hand, and an agent
 * registering a walk-in should not be blocked on an employer's name.
 *
 * The one cross-field rule is the identity document: a type and a number are
 * both-or-neither, matching `IdentityDocument`'s compact constructor and the
 * `party_identity_document_complete` CHECK. Enforced here so the user sees a
 * field error rather than a 400 from the domain.
 */

const ID_TYPES = ['NATIONAL_ID', 'PASSPORT', 'DRIVING_LICENCE', 'VOTER_ID'] as const;
const SEXES = ['FEMALE', 'MALE'] as const;
const SMOKER_STATUSES = ['SMOKER', 'NON_SMOKER', 'UNKNOWN'] as const;

/** Empty string is the "not answered" option on every optional select. */
const optionalEnum = <T extends readonly [string, ...string[]]>(values: T) =>
  z.union([z.enum(values), z.literal('')]);

export const registerIndividualFormSchema = z
  .object({
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

    sex: optionalEnum(SEXES),
    smokerStatus: optionalEnum(SMOKER_STATUSES),

    idType: optionalEnum(ID_TYPES),
    idNumber: z.string().trim().max(50, 'Cannot exceed 50 characters'),

    occupation: z.string().trim().max(120, 'Cannot exceed 120 characters'),
    occupationClass: z.string().trim().max(30, 'Cannot exceed 30 characters'),
    employerName: z.string().trim().max(255, 'Cannot exceed 255 characters'),
    nationality: z
      .string()
      .trim()
      .refine((v) => v === '' || /^[A-Za-z]{2}$/.test(v), 'Two-letter country code, e.g. TZ'),

    addressLine: z.string().trim().max(255, 'Cannot exceed 255 characters'),
    ward: z.string().trim().max(100, 'Cannot exceed 100 characters'),
    district: z.string().trim().max(100, 'Cannot exceed 100 characters'),
    region: z.string().trim().max(100, 'Cannot exceed 100 characters'),
    postalCode: z.string().trim().max(20, 'Cannot exceed 20 characters'),
  })
  .superRefine((values, ctx) => {
    const hasType = values.idType !== '';
    const hasNumber = values.idNumber !== '';
    if (hasNumber && !hasType) {
      ctx.addIssue({
        code: 'custom',
        path: ['idType'],
        message: 'Choose the document type for this number',
      });
    }
    if (hasType && !hasNumber) {
      ctx.addIssue({
        code: 'custom',
        path: ['idNumber'],
        message: 'Enter the number on the document',
      });
    }
  });

export type RegisterIndividualFormValues = z.infer<typeof registerIndividualFormSchema>;

export function blankRegisterIndividualForm(): RegisterIndividualFormValues {
  return {
    fullName: '',
    dateOfBirth: '',
    phoneNumber: '',
    email: '',
    sex: '',
    smokerStatus: '',
    idType: '',
    idNumber: '',
    occupation: '',
    occupationClass: '',
    employerName: '',
    // A visible default the user can change, not a value written on their behalf:
    // the overwhelming majority of registrations here are Tanzanian, and the field
    // is on screen with TZ in it rather than being silently defaulted server-side.
    nationality: 'TZ',
    addressLine: '',
    ward: '',
    district: '',
    region: '',
    postalCode: '',
  };
}

export function toApiRequest(values: RegisterIndividualFormValues): RegisterIndividualRequest {
  const phoneNumber = values.phoneNumber.trim();
  const email = values.email.trim();

  // Omit rather than send empty strings. An absent field means "not recorded";
  // "" would be a recorded blank, and the two are not the same thing on a KYC
  // register -- the same distinction the backend keeps between a null
  // smokerStatus and UNKNOWN.
  const address = {
    ...(values.addressLine && { line: values.addressLine }),
    ...(values.ward && { ward: values.ward }),
    ...(values.district && { district: values.district }),
    ...(values.region && { region: values.region }),
    ...(values.postalCode && { postalCode: values.postalCode }),
  };

  return {
    fullName: values.fullName.trim(),
    dateOfBirth: values.dateOfBirth,
    contactInfo: {
      ...(phoneNumber && { phoneNumber }),
      ...(email && { email }),
    },
    ...(values.sex && { sex: values.sex }),
    ...(values.smokerStatus && { smokerStatus: values.smokerStatus }),
    ...(values.idType && { idType: values.idType }),
    ...(values.idNumber && { idNumber: values.idNumber }),
    ...(values.occupation && { occupation: values.occupation }),
    ...(values.occupationClass && { occupationClass: values.occupationClass }),
    ...(values.employerName && { employerName: values.employerName }),
    ...(values.nationality && { nationality: values.nationality.toUpperCase() }),
    ...(Object.keys(address).length > 0 && { address }),
  };
}
