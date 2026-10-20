import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import type { ApiError } from '@/lib/apiError';

/**
 * The field a party's "already belongs to another client" refusal is about (2026-10-20), keyed by the server's own
 * error codes (party.infrastructure.PartyExceptionHandler).
 */
const FIELD_BY_CODE: Record<string, string> = {
  DUPLICATE_CLIENT_REFERENCE: 'clientReference',
  DUPLICATE_IDENTITY_DOCUMENT: 'idNumber',
  DUPLICATE_REGISTRATION_NUMBER: 'registrationNumber',
};

/**
 * Puts a duplicate refusal under the field it names, where it reads as "this one" and -- like every field error on
 * these forms -- goes the moment that field is changed. Returns whether it did, so the caller shows the general error
 * box only for refusals that belong to no one field.
 */
export function placeConflictOnField<T extends FieldValues>(
  error: Pick<ApiError, 'errorCode' | 'detail' | 'title'> | null | undefined,
  setError: UseFormSetError<T>,
  fields: readonly string[],
): boolean {
  const field = error?.errorCode ? FIELD_BY_CODE[error.errorCode] : undefined;
  if (!field || !fields.includes(field)) return false;
  setError(field as Path<T>, { type: 'server', message: error?.detail ?? error?.title ?? 'Already in use' },
    { shouldFocus: true });
  return true;
}

/** Whether a refusal will be shown under a field rather than in the form's general error box. */
export function isFieldConflict(error: Pick<ApiError, 'errorCode'> | null | undefined, fields: readonly string[]): boolean {
  const field = error?.errorCode ? FIELD_BY_CODE[error.errorCode] : undefined;
  return !!field && fields.includes(field);
}

/** The person form's fields a duplicate can name. */
export const PERSON_CONFLICT_FIELDS = ['clientReference', 'idNumber'] as const;
/** The company form's. */
export const COMPANY_CONFLICT_FIELDS = ['registrationNumber'] as const;
