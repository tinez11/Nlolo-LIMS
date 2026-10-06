import { describe, expect, it } from 'vitest';
import type { JournalTemplateView } from '@/api/types';
import { blankJournal, fromTemplate, manualJournalSchema, toInput, totals } from './manualJournalForm';

const payroll = () => ({
  ...blankJournal('2026-10'),
  title: 'October payroll',
  reason: 'Payroll summary',
  lines: [
    { accountCode: '8110', side: 'DR' as const, amount: '4500000.00', description: 'Salaries', branch: 'DSM', fund: '', reference: '' },
    { accountCode: '2640', side: 'CR' as const, amount: '4500000', description: '', branch: '', fund: '', reference: 'PAY-10' },
  ],
});

describe('manualJournalSchema', () => {
  it('accepts a balanced journal and sends it as the server takes it', () => {
    const parsed = manualJournalSchema.parse(payroll());
    const input = toInput(parsed);
    expect(input.lines).toHaveLength(2);
    expect(input.lines[0]?.amount).toBe(4500000);
    expect(input.lines[0]?.branch).toBe('DSM');
    expect(input.lines[1]?.reference).toBe('PAY-10');
    expect(input.lines[1]?.referenceType).toBe('DOCUMENT');
    expect(input.reasonCode).toBeNull();
    expect(input.autoReverseOn).toBeNull();
  });

  it('says by how much an unbalanced journal is out', () => {
    const v = payroll();
    v.lines[1] = { ...v.lines[1]!, amount: '4400000' };
    const result = manualJournalSchema.safeParse(v);
    expect(result.success).toBe(false);
    expect(result.error?.issues.map((i) => i.message)).toContain('Debits 4500000.00 and credits 4400000.00 differ by 100000.00');
  });

  it('refuses a malformed account or amount and a missing title', () => {
    const v = payroll();
    v.title = ' ';
    v.lines[0] = { ...v.lines[0]!, accountCode: '81', amount: '1.234' };
    const messages = manualJournalSchema.safeParse(v).error?.issues.map((i) => i.message) ?? [];
    expect(messages).toEqual(expect.arrayContaining(['A journal has a title', 'A four-digit account', 'An amount, at most two decimals']));
  });
});

describe('templates and totals', () => {
  it('a template fills accounts and sides and leaves the amounts to the preparer', () => {
    const template: JournalTemplateView = {
      id: 'M-01',
      source: 'GUIDE',
      title: 'Shares issued and fully paid',
      when: null,
      postedBy: null,
      reasonCode: null,
      lines: [
        { side: 'DR', accountCode: '1110', accountName: 'Main operating bank account', amount: null, description: null },
        { side: 'CR', accountCode: '3110', accountName: 'Ordinary share capital', amount: null, description: null },
      ],
    };
    const v = fromTemplate(template, '2026-10');
    expect(v.title).toBe('Shares issued and fully paid');
    expect(v.templateId).toBe('M-01');
    expect(v.lines.map((l) => `${l.side} ${l.accountCode} ${l.amount}`)).toEqual(['DR 1110 ', 'CR 3110 ']);
  });

  it('totals count only amounts that are numbers', () => {
    expect(totals([{ side: 'DR', amount: '10.50' }, { side: 'CR', amount: 'x' }, { side: 'CR', amount: '4' }])).toEqual({
      debit: 10.5,
      credit: 4,
    });
  });
});
