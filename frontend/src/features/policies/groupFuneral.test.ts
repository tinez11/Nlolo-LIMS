import { describe, expect, it } from 'vitest';
import type { FuneralTermsView } from '@/api/types';
import {
  blankFamily,
  familyLabels,
  fromLives,
  groupPlans,
  incompleteFamilies,
  monthlyBill,
  nextReference,
  planBenefit,
  toLives,
  type FamilyRow,
} from './groupFuneral';

const juma: FamilyRow = {
  reference: 'M001',
  mainName: 'Juma Ali',
  mainDateOfBirth: '1986-05-12',
  mainSex: 'MALE',
  beneficiaryName: 'Asha Juma',
  beneficiaryRelationship: 'Spouse',
  beneficiaryPhone: '',
  dependants: [
    { role: 'SPOUSE', fullName: 'Asha Juma', dateOfBirth: '1988-02-01', sex: '', student: false },
    { role: 'CHILD', fullName: 'Neema Juma', dateOfBirth: '2016-07-20', sex: 'FEMALE', student: false },
  ],
};

describe('group funeral families', () => {
  it('numbers a new family after the ones already used', () => {
    expect(nextReference([])).toBe('M001');
    expect(nextReference([juma, { ...juma, reference: 'm003' }])).toBe('M002');
    expect(blankFamily([juma]).reference).toBe('M002');
  });

  it('sends each family main member first, the beneficiary on the main member only', () => {
    const lives = toLives([juma]);
    expect(lives.map((l) => `${l.memberReference} ${l.role} ${l.fullName}`)).toEqual([
      'M001 MAIN_MEMBER Juma Ali',
      'M001 SPOUSE Asha Juma',
      'M001 CHILD Neema Juma',
    ]);
    expect(lives[0].beneficiaryName).toBe('Asha Juma');
    expect(lives[0].beneficiaryPhone).toBeNull();
    expect(lives[1].beneficiaryName).toBeNull();
  });

  it('reads the lines of a file back into the same families', () => {
    expect(fromLives(toLives([juma]))).toEqual([juma]);
  });

  it('keeps a member number with no main member as a family to finish', () => {
    const families = fromLives([{ memberReference: 'M009', role: 'CHILD', fullName: 'Orphan', dateOfBirth: '2020-01-01' }]);
    expect(families).toHaveLength(1);
    expect(families[0].mainName).toBe('');
    expect(incompleteFamilies(families)).toContain("M009: the main member's name is required");
  });

  it('names what is missing before anything is sent', () => {
    expect(incompleteFamilies([])).toEqual(['Add at least one member']);
    expect(incompleteFamilies([juma])).toEqual([]);
    expect(incompleteFamilies([juma, { ...juma, dependants: [] }])).toEqual(['M001: the member number is used twice']);
    expect(incompleteFamilies([{ ...juma, dependants: [{ ...juma.dependants[0], dateOfBirth: '' }] }]))
      .toEqual(['M001: Asha Juma needs a date of birth']);
  });
});

describe('group funeral plans', () => {
  const terms = {
    plans: [
      { planCode: 'A1', name: 'Plan A1', groupMonthlyRate: 3000 },
      { planCode: 'B', name: 'Plan B', groupMonthlyRate: null },
    ],
    benefits: [{ planCode: 'A1', role: 'CHILD', benefit: 500000 }],
    soldAs: 'GROUP',
  } as unknown as FuneralTermsView;

  it('offers only plans with a group rate, on a version sold to groups', () => {
    expect(groupPlans(terms)).toEqual([{ planCode: 'A1', name: 'Plan A1', rate: 3000 }]);
    expect(groupPlans({ ...terms, soldAs: 'INDIVIDUAL' })).toEqual([]);
    expect(groupPlans(null)).toEqual([]);
  });

  it("reads a role's benefit off the plan", () => {
    expect(planBenefit(terms, 'A1', 'CHILD')).toBe(500000);
    expect(planBenefit(terms, 'A1', 'PARENT')).toBeNull();
  });

  it('labels every life with its family for the claim form', () => {
    expect(familyLabels([{ memberReference: 'M001', mainMemberName: 'Juma Ali',
      lives: [{ coveredLifeId: 'a' }, { coveredLifeId: 'b' }] }])).toEqual({ a: 'M001 Juma Ali', b: 'M001 Juma Ali' });
  });

  it('states the bill as members x rate', () => {
    expect(monthlyBill(2, 3000, 'TZS')).toBe('2 members x TZS 3,000.00 = TZS 6,000.00 a month');
    expect(monthlyBill(1, 3000, 'TZS')).toBe('1 member x TZS 3,000.00 = TZS 3,000.00 a month');
  });
});
