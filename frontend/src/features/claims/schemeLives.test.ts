import { describe, expect, it } from 'vitest';
import type { CoveredLifeView, GroupFuneralFamilyView } from '@/api/types';
import { claimableLives, familyLabel, familyOfClaimant, lifeLabel, matchFamilies } from './schemeLives';

const life = (over: Partial<CoveredLifeView>): CoveredLifeView => ({
  coveredLifeId: crypto.randomUUID(), role: 'CHILD', fullName: 'Neema', dateOfBirth: '2016-01-01', sex: null,
  idNumber: null, student: false, partyId: null, benefit: 50000, yearlyPremium: 0, pricedAtAge: 10,
  coverStart: '2026-01-01', waitingPeriodEnds: null, coverEnd: null, status: 'ACTIVE', endReason: null, endedOn: null,
  ...over,
});

const family = (reference: string, main: string, lives: CoveredLifeView[]): GroupFuneralFamilyView => ({
  policyMemberId: `m-${reference}`, memberReference: reference, mainMemberName: main, status: 'ACTIVE',
  joinedOn: '2026-01-01', leftOn: null, beneficiaryName: null, beneficiaryRelationship: null, beneficiaryPhone: null,
  familyCover: 0, lives,
});

const martin = family('M001', 'martin lema', [life({ role: 'MAIN_MEMBER', fullName: 'martin lema', partyId: 'p-martin' })]);
const rehema = family('M002', 'Rehema Said', [life({ role: 'MAIN_MEMBER', fullName: 'Rehema Said' })]);
const juma = family('M013', 'Juma Ali', [life({ role: 'MAIN_MEMBER', fullName: 'Juma Ali' })]);

describe('choosing who died on a scheme', () => {
  it('finds a family by member number or main member name, any case, any part', () => {
    const all = [martin, rehema, juma];
    expect(matchFamilies(all, '')).toEqual(all);
    expect(matchFamilies(all, 'm013')).toEqual([juma]);
    expect(matchFamilies(all, 'LEMA')).toEqual([martin]);
    expect(matchFamilies(all, 'M00')).toEqual([martin, rehema]);
    expect(matchFamilies(all, 'nobody')).toEqual([]);
  });

  it('knows the family from the claimant when the claimant is its main member', () => {
    expect(familyOfClaimant([rehema, martin], 'p-martin')).toBe(martin);
    expect(familyOfClaimant([rehema, martin], 'p-someone-else')).toBeNull();
    expect(familyOfClaimant([rehema, martin], '')).toBeNull();
  });

  it('offers covered lives first, then lives whose cover ended, never a life already claimed as dead', () => {
    const covered = life({ fullName: 'Neema' });
    const removed = life({ fullName: 'Baraka', status: 'ENDED', endReason: 'REMOVED', endedOn: '2026-03-01' });
    const died = life({ fullName: 'Imani', status: 'ENDED', endReason: 'DECEASED', endedOn: '2026-02-01' });
    expect(claimableLives([removed, died, covered])).toEqual([covered, removed]);
    expect(lifeLabel(covered)).toBe('Neema (child)');
    expect(lifeLabel(removed)).toMatch(/^Baraka \(child\) — cover ended .*2026/);
    expect(lifeLabel(life({ role: 'MAIN_MEMBER', fullName: 'martin lema' }))).toBe('martin lema (main member)');
  });

  it('labels a family by its member number and main member', () => {
    expect(familyLabel(martin)).toBe('M001 · martin lema');
  });
});
