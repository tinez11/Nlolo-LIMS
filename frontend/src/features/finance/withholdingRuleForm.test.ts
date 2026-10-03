import { describe, expect, it } from 'vitest';
import { withholdingRuleSchema } from './withholdingRuleForm';

const messages = (r: { success: boolean; error?: { issues: { message: string }[] } }) =>
  r.success ? [] : r.error!.issues.map((i) => i.message);

const valid = { annuity: true, ratePercent: '10', effectiveFrom: '2026-11-01', effectiveTo: '', legalReference: 'Income Tax Act s.82' };

describe('withholdingRuleSchema', () => {
  it('accepts a rule for annuity income', () => {
    expect(withholdingRuleSchema.safeParse(valid).success).toBe(true);
  });
  it('refuses a rate of 0 or 100 in the server’s words', () => {
    expect(messages(withholdingRuleSchema.safeParse({ ...valid, ratePercent: '0' }))).toContain(
      'A withholding rate must be greater than 0% and less than 100%',
    );
  });
  it('needs a legal reference, a start date, and a kind', () => {
    expect(messages(withholdingRuleSchema.safeParse({ ...valid, legalReference: ' ' }))).toContain(
      'A withholding rule names the law or ruling it applies',
    );
    expect(messages(withholdingRuleSchema.safeParse({ ...valid, effectiveFrom: '' }))).toContain(
      'A withholding rule needs the date it takes effect',
    );
    expect(messages(withholdingRuleSchema.safeParse({ ...valid, annuity: false }))).toContain(
      'Choose the payouts this rule withholds from',
    );
  });
  it('refuses an end before the start', () => {
    expect(messages(withholdingRuleSchema.safeParse({ ...valid, effectiveTo: '2026-10-01' }))).toContain(
      'A withholding rule cannot end before it begins',
    );
  });
});
