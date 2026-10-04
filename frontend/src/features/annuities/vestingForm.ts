import { z } from 'zod';
import type { VestingInstructionInput } from '@/api/types';
import { ISO_DATE_PATTERN } from '@/lib/patterns';

/**
 * A vesting instruction as the form holds it (product step 5 D2), and `VestingRules` rule for rule
 * and message for message. The window, the target and the cap are the pension's own, so the schema
 * is built per pension.
 */
export interface VestingFormContext {
  today: string;
  target: string;
  earliest: string;
  latest: string;
  /** The lump-sum cap as the server sends it: a decimal string, no trailing zeros. */
  cap: string;
  /** The current version's joint-life form codes. */
  jointForms: readonly string[];
}

export function vestingFormSchema(c: VestingFormContext) {
  return z
    .object({
      vestingDate: z.string().trim().refine((v) => ISO_DATE_PATTERN.test(v), 'Choose the vesting date'),
      formCode: z.string().trim().min(1, 'Choose the form'),
      frequency: z.string().trim().min(1, 'Choose how often the income is paid'),
      jointLifePartyId: z.string().trim(),
      lumpSumPercent: z.string().trim(),
      contributions: z.string().trim(),
    })
    .superRefine((v, ctx) => {
      const issue = (path: string, message: string) => ctx.addIssue({ code: 'custom', path: [path], message });
      if (ISO_DATE_PATTERN.test(v.vestingDate)) {
        // ISO dates compare as strings.
        if (v.vestingDate < c.today) issue('vestingDate', 'A vesting date cannot be in the past');
        else if (v.vestingDate < c.earliest) {
          issue('vestingDate', `The earliest this pension can vest is ${c.earliest}, at the minimum vesting age`);
        } else if (v.vestingDate > c.latest) {
          issue('vestingDate', `The latest this pension can be deferred to is ${c.latest}, at the maximum vesting age`);
        }
        if (v.vestingDate > c.target && v.contributions === '') {
          issue('contributions', `A deferral must say whether contributions continue to the new date or stop at ${c.target}`);
        }
      }
      const pct = Number(v.lumpSumPercent);
      if (v.lumpSumPercent === '' || Number.isNaN(pct) || pct < 0 || pct > Number(c.cap)) {
        issue('lumpSumPercent', `The lump sum can be from 0% to ${c.cap}% of the balance`);
      }
      if (c.jointForms.includes(v.formCode) && v.jointLifePartyId === '') {
        issue('jointLifePartyId', `Form ${v.formCode} is joint-life: name the joint life`);
      }
    });
}

export type VestingFormValues = z.infer<ReturnType<typeof vestingFormSchema>>;

/** Contributions are sent only on a deferral, and a joint life only on a joint form -- as the server insists. */
export function toVestingInstruction(v: VestingFormValues, c: VestingFormContext): VestingInstructionInput {
  return {
    vestingDate: v.vestingDate,
    formCode: v.formCode,
    frequency: v.frequency as NonNullable<VestingInstructionInput['frequency']>,
    jointLifePartyId: c.jointForms.includes(v.formCode) ? v.jointLifePartyId : null,
    lumpSumPercent: Number(v.lumpSumPercent),
    contributions: v.vestingDate > c.target ? (v.contributions as 'CONTINUE' | 'STOP') : null,
  };
}
