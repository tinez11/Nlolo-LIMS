import { describe, expect, it } from 'vitest';
import { blankOpenCaseForm, openCaseFormSchema, toApiRequest } from './openCaseForm';

const valid = () => ({
  ...blankOpenCaseForm(),
  applicantPartyId: 'd9937444-3873-4336-9cb7-addb486f3e1b',
  productId: '9924cbb2-8adb-4be4-b0a6-2835e4ad7373',
  productVersionId: '76d868df-d22d-4044-8107-42bb3ab5107c',
  sumAssuredAmount: '6000000.00',
  sumAssuredCurrency: 'TZS',
  premiumFrequency: 'MONTHLY' as const,
  isUnitLinked: true,
  ulPremium: '100000',
  ulSplit: [
    { fundCode: 'EQ-GROWTH', percent: '60' },
    { fundCode: 'MM-CASH', percent: '40' },
  ],
});

const messages = (input: unknown) => {
  const r = openCaseFormSchema.safeParse(input);
  return r.success ? [] : r.error.issues.map((i) => i.message);
};

describe('a unit-linked case (UnitLinkedChoices)', () => {
  it('accepts a premium, a frequency and a split totalling 100%', () => {
    expect(messages(valid())).toEqual([]);
  });

  it('refuses a split that does not total 100%, in the server words', () => {
    expect(messages({ ...valid(), ulSplit: [{ fundCode: 'EQ-GROWTH', percent: '60' }, { fundCode: 'MM-CASH', percent: '30' }] })).toContain(
      'The fund split totals 90%; it must total 100%',
    );
  });

  it('refuses a fractional share, and no split at all', () => {
    expect(messages({ ...valid(), ulSplit: [{ fundCode: 'EQ-GROWTH', percent: '99.5' }, { fundCode: 'MM-CASH', percent: '0.5' }] })).toContain(
      "Each fund's share is a whole percent from 1 to 100",
    );
    expect(messages({ ...valid(), ulSplit: [{ fundCode: 'EQ-GROWTH', percent: '' }] })).toContain(
      'A unit-linked case says how each premium is split across the funds',
    );
  });

  it('needs the premium and the frequency', () => {
    const m = messages({ ...valid(), ulPremium: '', premiumFrequency: '' });
    expect(m).toContain('The premium the customer chose, as an amount above zero');
    expect(m).toContain('Choose how often the premium is paid');
  });

  it('sends the choice with the case, leaving funds with no share out of the split', () => {
    const parsed = openCaseFormSchema.parse({ ...valid(), ulSplit: [...valid().ulSplit, { fundCode: 'BOND', percent: '' }] });
    const request = toApiRequest(parsed);
    expect(request.unitLinked).toEqual({
      split: [
        { fundCode: 'EQ-GROWTH', percent: 60 },
        { fundCode: 'MM-CASH', percent: 40 },
      ],
      premium: 100000,
      frequency: 'MONTHLY',
      sumAssured: 6000000,
    });
    expect(request.premiumFrequency).toBe('MONTHLY');
    expect(request.requestedTermMonths).toBeUndefined();
  });

  it('checks nothing unit-linked on another case', () => {
    expect(messages({ ...valid(), isUnitLinked: false, ulSplit: [], ulPremium: '' })).toEqual([]);
  });
});
