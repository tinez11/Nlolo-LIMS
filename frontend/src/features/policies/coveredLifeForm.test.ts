import { describe, expect, it } from 'vitest';
import { awaitingTakeover, blankCoveredLife, coveredLifeFormSchema, identifyFormSchema, toAddCoveredLife } from './coveredLifeForm';

const messages = (input: unknown, schema: { safeParse: typeof coveredLifeFormSchema.safeParse | typeof identifyFormSchema.safeParse } = coveredLifeFormSchema) => {
  const r = schema.safeParse(input);
  return r.success ? [] : r.error.issues.map((i) => i.message);
};

describe('adding a covered life', () => {
  it('needs a name and a date of birth', () => {
    expect(messages(blankCoveredLife())).toEqual(['The full name is required', 'The date of birth is required']);
  });

  it('sends only a child as a student', () => {
    const parsed = coveredLifeFormSchema.parse({ ...blankCoveredLife(), role: 'PARENT', fullName: 'Bibi',
      dateOfBirth: '1955-01-01', student: true });
    expect(toAddCoveredLife(parsed)).toEqual({ role: 'PARENT', fullName: 'Bibi', dateOfBirth: '1955-01-01', sex: null,
      idNumber: null, student: false });
  });
});

describe('identifying a life', () => {
  it('needs the document and its number', () => {
    expect(messages({ idType: '', idNumber: '', phoneNumber: '', sex: '' }, identifyFormSchema))
      .toEqual(['Choose the identity document', 'The document number is required']);
  });
});

describe('awaitingTakeover', () => {
  const main = (status: string, endReason: string | null) => ({ role: 'MAIN_MEMBER', status, endReason, coverEnd: null });
  const spouse = (coverEnd: string | null) => ({ role: 'SPOUSE', status: 'ACTIVE', endReason: null, coverEnd });

  it('is waiting when the main member died and the spouse is still covered', () => {
    expect(awaitingTakeover([main('ENDED', 'DECEASED'), spouse(null)])).toBe(true);
  });

  it('is not waiting while the main member is alive, under free cover, or with no spouse', () => {
    expect(awaitingTakeover([main('ACTIVE', null), spouse(null)])).toBe(false);
    expect(awaitingTakeover([main('ENDED', 'DECEASED'), spouse('2026-11-04')])).toBe(false);
    expect(awaitingTakeover([main('ENDED', 'DECEASED')])).toBe(false);
  });
});
