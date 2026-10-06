import { z } from 'zod';
import type { JournalTemplateView, ManualJournalInput, ManualJournalView } from '@/api/types';

/**
 * A manual journal as the editor holds it (IFRS 17 I4). Amounts are text while typed. The console checks what it
 * can see -- balance, sides, amounts, a title -- and the server checks everything again at submit and at approval,
 * listing every problem: accounts and their modes, the reason code a BOTH account needs, documents, the period.
 */
const AMOUNT = /^\d{1,15}(\.\d{1,2})?$/;

export const lineSchema = z.object({
  accountCode: z.string().trim().regex(/^\d{4}$/, 'A four-digit account'),
  side: z.enum(['DR', 'CR']),
  amount: z.string().trim().regex(AMOUNT, 'An amount, at most two decimals'),
  description: z.string().trim().max(300),
  branch: z.string().trim(),
  fund: z.string().trim().max(30),
  reference: z.string().trim().max(100),
});

export const manualJournalSchema = z
  .object({
    title: z.string().trim().min(1, 'A journal has a title').max(200),
    period: z.string().trim().regex(/^\d{4}-(0[1-9]|1[0-2])$/, 'A period is YYYY-MM'),
    reason: z.string().trim().max(500),
    reasonCode: z.string().trim(),
    autoReverseOn: z.string().trim(),
    templateId: z.string().trim(),
    lines: z.array(lineSchema).min(2, 'A journal has at least one Dr and one Cr line'),
  })
  .superRefine((v, ctx) => {
    const { debit, credit } = totals(v.lines);
    if (debit === 0 || credit === 0) {
      ctx.addIssue({ code: 'custom', path: ['lines'], message: 'A journal has at least one Dr and one Cr line' });
    } else if (Math.round(debit * 100) !== Math.round(credit * 100)) {
      ctx.addIssue({
        code: 'custom',
        path: ['lines'],
        message: `Debits ${debit.toFixed(2)} and credits ${credit.toFixed(2)} differ by ${Math.abs(debit - credit).toFixed(2)}`,
      });
    }
  });

export type ManualJournalValues = z.infer<typeof manualJournalSchema>;
export type LineValues = z.infer<typeof lineSchema>;

export function blankLine(side: 'DR' | 'CR' = 'DR', accountCode = ''): LineValues {
  return { accountCode, side, amount: '', description: '', branch: '', fund: '', reference: '' };
}

export function blankJournal(period: string): ManualJournalValues {
  return {
    title: '',
    period,
    reason: '',
    reasonCode: '',
    autoReverseOn: '',
    templateId: '',
    lines: [blankLine('DR'), blankLine('CR')],
  };
}

/** A guide or saved template's lines, amounts left for the preparer (a saved one keeps its own). */
export function fromTemplate(template: JournalTemplateView, period: string): ManualJournalValues {
  return {
    ...blankJournal(period),
    title: template.title,
    reasonCode: template.reasonCode ?? '',
    templateId: template.id,
    lines: template.lines.map((l) => ({
      ...blankLine(l.side, l.accountCode),
      amount: l.amount != null ? String(l.amount) : '',
      description: l.description ?? '',
    })),
  };
}

export function fromJournal(j: ManualJournalView): ManualJournalValues {
  return {
    title: j.title,
    period: j.period,
    reason: j.reason ?? '',
    reasonCode: j.reasonCode ?? '',
    autoReverseOn: j.autoReverseOn ?? '',
    templateId: j.templateId ?? '',
    lines: j.lines.map((l) => ({
      accountCode: l.accountCode,
      side: l.side,
      amount: String(l.amount),
      description: l.description ?? '',
      branch: l.branch ?? '',
      fund: l.fund ?? '',
      reference: l.reference ?? '',
    })),
  };
}

const orNull = (s: string) => (s.trim() === '' ? null : s.trim());

export function toInput(v: ManualJournalValues): ManualJournalInput {
  return {
    title: v.title,
    period: v.period,
    currency: 'TZS',
    reason: orNull(v.reason),
    reasonCode: orNull(v.reasonCode),
    autoReverseOn: orNull(v.autoReverseOn),
    templateId: orNull(v.templateId),
    lines: v.lines.map((l) => ({
      accountCode: l.accountCode,
      side: l.side,
      amount: Number(l.amount),
      description: orNull(l.description),
      branch: orNull(l.branch),
      fund: orNull(l.fund),
      referenceType: orNull(l.reference) ? 'DOCUMENT' : null,
      reference: orNull(l.reference),
    })),
  };
}

export const STATUS_LABEL: Record<string, string> = {
  DRAFT: 'Draft',
  SUBMITTED: 'Awaiting approval',
  APPROVED: 'Posted',
  REJECTED: 'Rejected',
};

/**
 * Where the platform's reversal of an accrual stands, in words, or null when the journal has none. A reversal
 * waiting on a locked period says so -- it posts only once the period is reopened.
 */
export function autoReversalLabel(j: Pick<ManualJournalView, 'autoReversal' | 'autoReverseOn'>): string | null {
  switch (j.autoReversal) {
    case 'SCHEDULED':
      return `Reverses automatically on ${j.autoReverseOn}`;
    case 'DUE':
      return `Reversal due on ${j.autoReverseOn}; the platform posts it within the hour`;
    case 'WAITING_PERIOD_LOCKED':
      return `Reversal waiting: the period of ${j.autoReverseOn} is locked. It posts once the period is reopened`;
    case 'REVERSED':
      return `Reversed automatically on ${j.autoReverseOn}`;
    default:
      return null;
  }
}

/** Live Dr and Cr totals of the lines typed so far (an amount not yet a number counts as nothing). */
export function totals(lines: { side: string; amount: string }[]): { debit: number; credit: number } {
  let debit = 0;
  let credit = 0;
  for (const l of lines) {
    const n = AMOUNT.test(l.amount.trim()) ? Number(l.amount) : 0;
    if (l.side === 'DR') debit += n;
    else credit += n;
  }
  return { debit, credit };
}
