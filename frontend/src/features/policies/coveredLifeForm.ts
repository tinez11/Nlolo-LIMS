import { z } from 'zod';
import { ISO_DATE_PATTERN } from '@/lib/patterns';

/**
 * A life added to a funeral policy (family funeral cover). The product's own rules -- the role's count,
 * entry age on the next premium date, roles the plan does not cover -- are checked by the server and
 * shown in its words; this only refuses what is missing.
 */
export const DEPENDANT_ROLES = ['SPOUSE', 'CHILD', 'PARENT', 'EXTENDED'] as const;

export const coveredLifeFormSchema = z.object({
  role: z.enum(DEPENDANT_ROLES),
  fullName: z.string().trim().min(1, 'The full name is required'),
  dateOfBirth: z.string().trim().refine((v) => ISO_DATE_PATTERN.test(v), 'The date of birth is required'),
  sex: z.string().trim(),
  idNumber: z.string().trim(),
  student: z.boolean(),
});

export type CoveredLifeFormValues = z.infer<typeof coveredLifeFormSchema>;

export function blankCoveredLife(): CoveredLifeFormValues {
  return { role: 'CHILD', fullName: '', dateOfBirth: '', sex: '', idNumber: '', student: false };
}

export function toAddCoveredLife(v: CoveredLifeFormValues) {
  return {
    role: v.role,
    fullName: v.fullName,
    dateOfBirth: v.dateOfBirth,
    sex: v.sex === '' ? null : v.sex,
    idNumber: v.idNumber === '' ? null : v.idNumber,
    // Only a child can be a student; anything else would be refused.
    student: v.role === 'CHILD' && v.student,
  };
}

/** The identity document seen at claim or takeover. */
export const identifyFormSchema = z.object({
  idType: z.string().trim().min(1, 'Choose the identity document'),
  idNumber: z.string().trim().min(1, 'The document number is required'),
  phoneNumber: z.string().trim(),
  sex: z.string().trim(),
});

export type IdentifyFormValues = z.infer<typeof identifyFormSchema>;

export function toIdentify(v: IdentifyFormValues) {
  return {
    idType: v.idType as 'NATIONAL_ID' | 'PASSPORT' | 'DRIVING_LICENCE' | 'VOTER_ID',
    idNumber: v.idNumber,
    phoneNumber: v.phoneNumber === '' ? null : v.phoneNumber,
    sex: v.sex === '' ? null : (v.sex as 'FEMALE' | 'MALE'),
  };
}

/**
 * Whether the main member's death left the policy waiting for the spouse to take over (plan R8): no main
 * member on cover, the deceased one ended DECEASED, and a spouse still covered with no scheduled end.
 * Free cover schedules the spouse off, and the policy ending ends them -- so only a takeover leaves this.
 */
export function awaitingTakeover(lives: { role: string; status: string; endReason?: string | null; coverEnd?: string | null }[]): boolean {
  const activeMain = lives.some((l) => l.role === 'MAIN_MEMBER' && l.status === 'ACTIVE');
  const deceasedMain = lives.some((l) => l.role === 'MAIN_MEMBER' && l.endReason === 'DECEASED');
  const coveredSpouse = lives.some((l) => l.role === 'SPOUSE' && l.status === 'ACTIVE' && !l.coverEnd);
  return !activeMain && deceasedMain && coveredSpouse;
}
