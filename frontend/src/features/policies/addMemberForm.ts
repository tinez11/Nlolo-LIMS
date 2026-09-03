import { z } from 'zod';
import type { BenefitBasis, GroupMemberInput } from '@/api/types';
import { todayIso } from '@/lib/dates';
import { AMOUNT_PATTERN } from '@/lib/money';
import { ISO_DATE_PATTERN, UUID_PATTERN } from '@/lib/patterns';

/**
 * Adding one life to a scheme.
 *
 * **The schema depends on the scheme**, which is why this is a factory rather
 * than a constant. `gradeCode` and `salaryAmount` are each required on exactly
 * one benefit basis and rejected on the others, mirroring `PolicyApiImpl`'s own
 * checks: a grade on a flat scheme is somebody who believes something about this
 * contract that is not true, and accepting-then-ignoring it would let a salaried
 * schedule be typed into a flat scheme and produce plausible, wrong numbers.
 *
 * The screen only renders the field the basis calls for, so the "rejected on the
 * others" half is unreachable through the UI. It is validated anyway: the rule
 * belongs to the contract, not to which inputs happen to be on screen today.
 */

/** The scheme facts this form has to validate against. */
export interface MemberFormContext {
  benefitBasis: BenefitBasis;
  /** Codes on the scheme's grade table. Empty unless GRADED. */
  gradeCodes: string[];
  /** The scheme's commencement date, if it has one — nobody may join before it. */
  commencementDate?: string | null;
  /** Today, injectable so the tests do not depend on the clock. */
  today?: string;
}

export interface MemberFormValues {
  memberPartyId: string;
  gradeCode: string;
  salaryAmount: string;
  joinedOn: string;
}

export function blankMemberForm(): MemberFormValues {
  return { memberPartyId: '', gradeCode: '', salaryAmount: '', joinedOn: '' };
}

export function memberFormSchema(context: MemberFormContext) {
  const today = context.today ?? todayIso();

  return z
    .object({
      memberPartyId: z
        .string()
        .trim()
        .min(1, 'Choose the person to add')
        .regex(UUID_PATTERN, 'Not a valid person'),
      gradeCode: z.string().trim(),
      salaryAmount: z.string().trim(),
      joinedOn: z
        .string()
        .trim()
        .refine((v) => v === '' || ISO_DATE_PATTERN.test(v), 'Not a valid date'),
    })
    .superRefine((values, ctx) => {
      switch (context.benefitBasis) {
        case 'FLAT':
          rejectPresent(ctx, 'gradeCode', values.gradeCode, 'This scheme pays a flat benefit, so a grade means nothing on it');
          rejectPresent(ctx, 'salaryAmount', values.salaryAmount, 'This scheme pays a flat benefit, so a salary means nothing on it');
          break;
        case 'SALARY_MULTIPLE':
          rejectPresent(ctx, 'gradeCode', values.gradeCode, 'This scheme values members by salary, so a grade means nothing on it');
          if (values.salaryAmount === '') {
            issue(ctx, 'salaryAmount', 'This scheme values members by salary, so every member needs one');
          } else if (!AMOUNT_PATTERN.test(values.salaryAmount)) {
            issue(ctx, 'salaryAmount', 'Must be an amount like 2000000.00');
          } else if (Number(values.salaryAmount) < 0.01) {
            issue(ctx, 'salaryAmount', 'Must be at least 0.01');
          }
          break;
        case 'GRADED':
          rejectPresent(ctx, 'salaryAmount', values.salaryAmount, 'This scheme values members by grade, so a salary means nothing on it');
          if (values.gradeCode === '') {
            issue(ctx, 'gradeCode', 'This scheme values members by grade, so every member needs one');
          } else if (!context.gradeCodes.includes(values.gradeCode)) {
            issue(
              ctx,
              'gradeCode',
              `Grade ${values.gradeCode} is not on this scheme (${context.gradeCodes.join(', ')})`,
            );
          }
          break;
      }

      // Both mirror a real 409. Checked here so the answer arrives while the
      // date field still has focus, rather than as a server rejection after the
      // form has been filled in and submitted.
      if (values.joinedOn !== '' && values.joinedOn > today) {
        issue(ctx, 'joinedOn', 'Cover cannot start in the future — record the member on the day it starts');
      }
      if (
        values.joinedOn !== '' &&
        context.commencementDate &&
        values.joinedOn < context.commencementDate
      ) {
        issue(ctx, 'joinedOn', `The scheme commenced on ${context.commencementDate}; nobody can join before that`);
      }
    });
}

function issue(ctx: z.RefinementCtx, path: keyof MemberFormValues, message: string) {
  ctx.addIssue({ code: 'custom', path: [path], message });
}

function rejectPresent(
  ctx: z.RefinementCtx,
  path: keyof MemberFormValues,
  value: string,
  message: string,
) {
  if (value !== '') issue(ctx, path, message);
}

/**
 * Blank optional fields are OMITTED, never sent as empty strings.
 *
 * `joinedOn: ''` would fail date parsing server-side, and `salaryAmount: ''`
 * would fail the amount pattern — where absent means "the scheme's commencement
 * date" and "this basis collects no salary" respectively. An empty string is not
 * a value on this wire; it is the absence of one, and has to be spelled that way.
 */
export function toApiRequest(values: MemberFormValues): GroupMemberInput {
  return {
    memberPartyId: values.memberPartyId.trim(),
    ...(values.gradeCode.trim() ? { gradeCode: values.gradeCode.trim() } : {}),
    ...(values.salaryAmount.trim() ? { salaryAmount: values.salaryAmount.trim() } : {}),
    ...(values.joinedOn.trim() ? { joinedOn: values.joinedOn.trim() } : {}),
  };
}
