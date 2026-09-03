import { z } from 'zod';
import type { IssueGroupSchemeRequest } from '@/api/types';
import { todayIso } from '@/lib/dates';
import { AMOUNT_PATTERN } from '@/lib/money';
import { CURRENCY_PATTERN, ISO_DATE_PATTERN, UUID_PATTERN } from '@/lib/patterns';

/**
 * Issuing a group scheme with its opening schedule.
 *
 * **No sum assured field**, and that is the shape of the whole thing: a scheme's
 * sum assured is the total of its members' cover, derived server-side from the
 * schedule. A field here would be a number somebody could type that disagrees
 * with the people underneath it.
 *
 * The three basis-dependent groups — flat amount, salary multiple, grade table —
 * are validated conditionally, exactly as `IssueGroupSchemeRequestDto` and
 * `PolicyApiImpl` do. Only the relevant one is rendered, so the "wrong basis"
 * branches are unreachable through the UI; they are validated anyway, because
 * the rule belongs to the contract rather than to today's screen.
 */

const optionalAmount = (label: string) =>
  z
    .string()
    .trim()
    .refine((v) => v === '' || AMOUNT_PATTERN.test(v), `${label} must be an amount like 2000000.00`)
    .refine((v) => v === '' || Number(v) >= 0.01, `${label} must be at least 0.01`);

export const gradeRowSchema = z.object({
  gradeCode: z.string().trim(),
  benefitAmount: z.string().trim(),
});

export const memberRowSchema = z.object({
  memberPartyId: z.string().trim(),
  gradeCode: z.string().trim(),
  salaryAmount: z.string().trim(),
});

export type GradeRowValues = z.infer<typeof gradeRowSchema>;
export type MemberRowValues = z.infer<typeof memberRowSchema>;

export interface GroupSchemeIssueFormValues {
  policyholderPartyId: string;
  productId: string;
  productVersionId: string;
  currency: string;
  benefitBasis: 'FLAT' | 'SALARY_MULTIPLE' | 'GRADED';
  flatBenefitAmount: string;
  salaryMultiple: string;
  fclAmount: string;
  grades: GradeRowValues[];
  openingSchedule: MemberRowValues[];
  premiumAmount: string;
  premiumCurrency: string;
  premiumFrequency: 'MONTHLY' | 'QUARTERLY' | 'ANNUALLY';
  commencementDate: string;
  policyTermMonths: string;
  reasonForManualIssue: string;
}

export function blankGradeRow(): GradeRowValues {
  return { gradeCode: '', benefitAmount: '' };
}

export function blankMemberRow(): MemberRowValues {
  return { memberPartyId: '', gradeCode: '', salaryAmount: '' };
}

export function blankGroupSchemeIssueForm(): GroupSchemeIssueFormValues {
  return {
    policyholderPartyId: '',
    productId: '',
    productVersionId: '',
    currency: 'TZS',
    benefitBasis: 'FLAT',
    flatBenefitAmount: '',
    salaryMultiple: '',
    fclAmount: '',
    grades: [],
    // One row to start: a scheme cannot be issued empty, so an empty table would
    // only ever be a state the user has to fix before they can do anything.
    openingSchedule: [blankMemberRow()],
    premiumAmount: '',
    premiumCurrency: 'TZS',
    premiumFrequency: 'ANNUALLY',
    commencementDate: '',
    policyTermMonths: '',
    reasonForManualIssue: '',
  };
}

export function groupSchemeIssueFormSchema(today: string = todayIso()) {
  return z
    .object({
      policyholderPartyId: z
        .string()
        .trim()
        .min(1, 'Choose the employer or association that holds the contract')
        .regex(UUID_PATTERN, 'Not a valid party'),
      productId: z.string().trim().min(1, 'Select a group product'),
      productVersionId: z.string().trim().min(1, 'Select a group product'),
      currency: z.string().trim().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS'),
      benefitBasis: z.enum(['FLAT', 'SALARY_MULTIPLE', 'GRADED']),
      flatBenefitAmount: optionalAmount('Benefit'),
      salaryMultiple: z
        .string()
        .trim()
        .refine((v) => v === '' || /^\d+(\.\d{1,2})?$/.test(v), 'Must be a number like 3 or 3.5')
        .refine((v) => v === '' || Number(v) > 0, 'Must be greater than zero'),
      // Blank means the scheme has NO free cover limit, which is a real design.
      // It is not zero, and zero is refused rather than reinterpreted.
      fclAmount: optionalAmount('Free cover limit'),
      grades: z.array(gradeRowSchema),
      openingSchedule: z.array(memberRowSchema).min(1, 'A scheme needs at least one member'),
      premiumAmount: z
        .string()
        .trim()
        .regex(AMOUNT_PATTERN, 'Must be an amount like 1200000.00')
        .refine((v) => Number(v) >= 0.01, 'Must be at least 0.01'),
      premiumCurrency: z.string().trim().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS'),
      premiumFrequency: z.enum(['MONTHLY', 'QUARTERLY', 'ANNUALLY']),
      commencementDate: z
        .string()
        .trim()
        .refine((v) => v === '' || ISO_DATE_PATTERN.test(v), 'Not a valid date'),
      policyTermMonths: z
        .string()
        .trim()
        .refine((v) => v === '' || /^\d+$/.test(v), 'Must be a whole number of months')
        .refine((v) => v === '' || Number(v) >= 1, 'Must be at least 1 month'),
      reasonForManualIssue: z.string().trim(),
    })
    .superRefine((values, ctx) => {
      const issue = (path: (string | number)[], message: string) =>
        ctx.addIssue({ code: 'custom', path, message });

      // --- The basis and its parameter must agree ---------------------------
      switch (values.benefitBasis) {
        case 'FLAT':
          if (values.flatBenefitAmount === '') {
            issue(['flatBenefitAmount'], 'A flat scheme needs the benefit every member gets');
          }
          if (values.grades.length > 0) {
            issue(['grades'], 'Only a graded scheme has a grade table');
          }
          break;
        case 'SALARY_MULTIPLE':
          if (values.salaryMultiple === '') {
            issue(['salaryMultiple'], 'A salary-multiple scheme needs a multiple, e.g. 3');
          }
          if (values.grades.length > 0) {
            issue(['grades'], 'Only a graded scheme has a grade table');
          }
          break;
        case 'GRADED':
          if (values.grades.length === 0) {
            issue(['grades'], 'A graded scheme needs at least one grade to value anybody');
          }
          break;
      }

      // --- The grade table --------------------------------------------------
      const seenGrades = new Set<string>();
      values.grades.forEach((grade, index) => {
        if (grade.gradeCode === '') {
          issue(['grades', index, 'gradeCode'], 'Every grade needs a code');
        } else if (seenGrades.has(grade.gradeCode)) {
          issue(['grades', index, 'gradeCode'], `Grade ${grade.gradeCode} is listed twice`);
        } else {
          seenGrades.add(grade.gradeCode);
        }
        if (grade.benefitAmount === '' || !AMOUNT_PATTERN.test(grade.benefitAmount)) {
          issue(['grades', index, 'benefitAmount'], 'Needs an amount like 10000000.00');
        } else if (Number(grade.benefitAmount) < 0.01) {
          issue(['grades', index, 'benefitAmount'], 'Must be at least 0.01');
        }
      });

      // --- The opening schedule --------------------------------------------
      const seenMembers = new Set<string>();
      values.openingSchedule.forEach((member, index) => {
        if (member.memberPartyId === '') {
          issue(['openingSchedule', index, 'memberPartyId'], 'Choose a person, or remove the row');
        } else if (!UUID_PATTERN.test(member.memberPartyId)) {
          issue(['openingSchedule', index, 'memberPartyId'], 'Not a valid person');
        } else if (seenMembers.has(member.memberPartyId)) {
          // The unique index would catch this as a 409 after a round trip. Here
          // it is caught next to the duplicated row, which is where the fix is.
          issue(['openingSchedule', index, 'memberPartyId'], 'This person is already on the schedule');
        } else {
          seenMembers.add(member.memberPartyId);
        }

        switch (values.benefitBasis) {
          case 'FLAT':
            break;
          case 'SALARY_MULTIPLE':
            if (member.salaryAmount === '' || !AMOUNT_PATTERN.test(member.salaryAmount)) {
              issue(['openingSchedule', index, 'salaryAmount'], 'Needs a salary like 4000000.00');
            }
            break;
          case 'GRADED':
            if (member.gradeCode === '') {
              issue(['openingSchedule', index, 'gradeCode'], 'Choose a grade');
            } else if (!seenGrades.has(member.gradeCode)) {
              issue(['openingSchedule', index, 'gradeCode'], 'Not a grade on this scheme');
            }
            break;
        }
      });

      // --- Commencement -----------------------------------------------------
      // Mirrors a real 409. Backdating is normal; forward-dating is refused
      // until the scheme total is date-aware end to end.
      if (values.commencementDate !== '' && values.commencementDate > today) {
        issue(
          ['commencementDate'],
          'A scheme cannot commence in the future yet — its total would not match its members until then',
        );
      }
    });
}

/**
 * Blank optional fields are OMITTED, never sent as empty strings.
 *
 * An empty `fclAmount` means "this scheme has no free cover limit" — a real
 * design — and sending `""` would fail the amount pattern rather than saying it.
 */
export function toApiRequest(values: GroupSchemeIssueFormValues): IssueGroupSchemeRequest {
  const basis = values.benefitBasis;
  return {
    policyholderPartyId: values.policyholderPartyId.trim(),
    productVersionId: values.productVersionId.trim(),
    // Present-but-nullable on the wire, matching manual issue: a scheme sold
    // direct has no broker, which is not the same as a missing field.
    agentOfRecordId: null,
    benefitBasis: basis,
    ...(basis === 'FLAT' && values.flatBenefitAmount
      ? { flatBenefitAmount: values.flatBenefitAmount.trim() }
      : {}),
    ...(basis === 'SALARY_MULTIPLE' && values.salaryMultiple
      ? { salaryMultiple: Number(values.salaryMultiple) }
      : {}),
    ...(values.fclAmount ? { fclAmount: values.fclAmount.trim() } : {}),
    currency: values.currency.trim().toUpperCase(),
    ...(basis === 'GRADED'
      ? {
          grades: values.grades.map((g) => ({
            gradeCode: g.gradeCode.trim(),
            benefitAmount: g.benefitAmount.trim(),
          })),
        }
      : {}),
    openingSchedule: values.openingSchedule.map((m) => ({
      memberPartyId: m.memberPartyId.trim(),
      ...(basis === 'GRADED' && m.gradeCode ? { gradeCode: m.gradeCode.trim() } : {}),
      ...(basis === 'SALARY_MULTIPLE' && m.salaryAmount
        ? { salaryAmount: m.salaryAmount.trim() }
        : {}),
    })),
    premium: {
      amount: values.premiumAmount.trim(),
      currencyCode: values.premiumCurrency.trim().toUpperCase(),
    },
    premiumFrequency: values.premiumFrequency,
    ...(values.commencementDate ? { commencementDate: values.commencementDate } : {}),
    ...(values.policyTermMonths ? { policyTermMonths: Number(values.policyTermMonths) } : {}),
    ...(values.reasonForManualIssue.trim()
      ? { reasonForManualIssue: values.reasonForManualIssue.trim() }
      : {}),
  };
}
