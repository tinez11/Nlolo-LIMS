import type { FuneralTermsView, GroupFuneralLife, GroupFuneralLifeInput } from '@/api/types';
import type { FuneralRoleName } from '@/features/products/funeralSchema';

/**
 * Group funeral schemes (2026-10-07): an association's families as the console edits them, and the
 * lines the server takes. Pure, so the shapes are tested without a page.
 *
 * A family is one main member (who carries the beneficiary they name) and their dependants, all under
 * the association's own member number. The server checks every family against the plan's role rules;
 * the checks here only stop an obviously incomplete row from being sent.
 */

export type DependantRole = Exclude<FuneralRoleName, 'MAIN_MEMBER'>;

export interface DependantRow {
  role: DependantRole;
  fullName: string;
  dateOfBirth: string;
  sex: string;
  student: boolean;
}

export interface FamilyRow {
  reference: string;
  mainName: string;
  mainDateOfBirth: string;
  mainSex: string;
  beneficiaryName: string;
  beneficiaryRelationship: string;
  beneficiaryPhone: string;
  dependants: DependantRow[];
}

/** The next unused association number, M001, M002, ... */
export function nextReference(families: FamilyRow[]): string {
  const used = new Set(families.map((f) => f.reference.trim().toUpperCase()));
  for (let n = 1; ; n++) {
    const candidate = `M${String(n).padStart(3, '0')}`;
    if (!used.has(candidate)) return candidate;
  }
}

export function blankFamily(families: FamilyRow[]): FamilyRow {
  return {
    reference: nextReference(families),
    mainName: '',
    mainDateOfBirth: '',
    mainSex: '',
    beneficiaryName: '',
    beneficiaryRelationship: '',
    beneficiaryPhone: '',
    dependants: [],
  };
}

export function blankDependant(): DependantRow {
  return { role: 'CHILD', fullName: '', dateOfBirth: '', sex: '', student: false };
}

const orNull = (s: string) => (s.trim() === '' ? null : s.trim());

/** A sex as the server takes it; anything else (blank, a typo from a file) as unrecorded. */
export function asSex(s: string): 'FEMALE' | 'MALE' | null {
  const t = s.trim().toUpperCase();
  return t === 'FEMALE' || t === 'MALE' ? t : null;
}

/** The families as the server's lines: each family's main member first, then their dependants. */
export function toLives(families: FamilyRow[]): GroupFuneralLifeInput[] {
  return families.flatMap((f) => {
    const reference = f.reference.trim();
    const main: GroupFuneralLifeInput = {
      memberReference: reference,
      role: 'MAIN_MEMBER',
      fullName: f.mainName.trim(),
      dateOfBirth: f.mainDateOfBirth || null,
      sex: asSex(f.mainSex),
      idNumber: null,
      student: false,
      beneficiaryName: orNull(f.beneficiaryName),
      beneficiaryRelationship: orNull(f.beneficiaryRelationship),
      beneficiaryPhone: orNull(f.beneficiaryPhone),
    };
    return [
      main,
      ...f.dependants.map((d): GroupFuneralLifeInput => ({
        memberReference: reference,
        role: d.role,
        fullName: d.fullName.trim(),
        dateOfBirth: d.dateOfBirth || null,
        sex: asSex(d.sex),
        idNumber: null,
        student: d.student,
        beneficiaryName: null,
        beneficiaryRelationship: null,
        beneficiaryPhone: null,
      })),
    ];
  });
}

/**
 * Lines (from the association's file) back into families, in the order each member number first
 * appears. A member number with no main member still becomes a family -- with its main member blank,
 * for the user to fill -- rather than losing its lives.
 */
export function fromLives(lives: GroupFuneralLife[]): FamilyRow[] {
  const byReference = new Map<string, FamilyRow>();
  for (const life of lives) {
    const reference = (life.memberReference ?? '').trim();
    let family = byReference.get(reference);
    if (!family) {
      family = { ...blankFamily([]), reference };
      byReference.set(reference, family);
    }
    if (life.role === 'MAIN_MEMBER') {
      family.mainName = life.fullName ?? '';
      family.mainDateOfBirth = life.dateOfBirth ?? '';
      family.mainSex = life.sex ?? '';
      family.beneficiaryName = life.beneficiaryName ?? '';
      family.beneficiaryRelationship = life.beneficiaryRelationship ?? '';
      family.beneficiaryPhone = life.beneficiaryPhone ?? '';
    } else if (life.role) {
      family.dependants.push({
        role: life.role,
        fullName: life.fullName ?? '',
        dateOfBirth: life.dateOfBirth ?? '',
        sex: life.sex ?? '',
        student: life.student === true,
      });
    }
  }
  return [...byReference.values()];
}

/** What stops the families being sent: a missing or repeated member number, a nameless or undated life. */
export function incompleteFamilies(families: FamilyRow[]): string[] {
  const problems: string[] = [];
  if (families.length === 0) problems.push('Add at least one member');
  const seen = new Set<string>();
  families.forEach((f, i) => {
    const label = f.reference.trim() || `Family ${i + 1}`;
    const key = f.reference.trim().toUpperCase();
    if (key === '') problems.push(`${label}: the member number is required`);
    else if (seen.has(key)) problems.push(`${label}: the member number is used twice`);
    seen.add(key);
    if (f.mainName.trim() === '') problems.push(`${label}: the main member's name is required`);
    if (f.mainDateOfBirth === '') problems.push(`${label}: the main member's date of birth is required`);
    f.dependants.forEach((d, j) => {
      const who = d.fullName.trim() || `dependant ${j + 1}`;
      if (d.fullName.trim() === '') problems.push(`${label}: ${who} needs a name`);
      if (d.dateOfBirth === '') problems.push(`${label}: ${who} needs a date of birth`);
    });
  });
  return problems;
}

export type RatePeriod = 'MONTHLY' | 'YEARLY';

/** The plans a scheme may be written on: a version sold to groups, each plan with its group rate and what it is per. */
export function groupPlans(terms: FuneralTermsView | null): { planCode: string; name: string; rate: number; period: RatePeriod }[] {
  if (!terms || terms.soldAs === 'INDIVIDUAL') return [];
  return terms.plans
    .filter((p) => p.groupMonthlyRate != null)
    .map((p) => ({ planCode: p.planCode, name: p.name, rate: p.groupMonthlyRate as number,
      period: (p.groupRatePeriod ?? 'MONTHLY') as RatePeriod }));
}

/** What a plan pays for a life in a role; null when the plan does not cover it. */
export function planBenefit(terms: FuneralTermsView | null, planCode: string, role: FuneralRoleName): number | null {
  return terms?.benefits.find((b) => b.planCode === planCode && b.role === role)?.benefit ?? null;
}

/** "2 members x 3,000.00 = 6,000.00 a month" (or "a year") -- the association's bill, as the server computes it. */
export function monthlyBill(members: number, rate: number, currency: string, period: RatePeriod = 'MONTHLY'): string {
  const fmt = (n: number) => n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return `${members} member${members === 1 ? '' : 's'} x ${currency} ${fmt(rate)} = ${currency} ${fmt(members * rate)} `
    + (period === 'YEARLY' ? 'a year' : 'a month');
}

/** Each life's family on a group funeral scheme, as "M001 Juma Ali" -- how a claim form says whose family a life is in. */
export function familyLabels(families: { memberReference?: string | null; mainMemberName?: string | null;
  lives: { coveredLifeId: string }[] }[]): Record<string, string> {
  const labels: Record<string, string> = {};
  for (const f of families) {
    for (const life of f.lives) labels[life.coveredLifeId] = `${f.memberReference ?? ''} ${f.mainMemberName ?? ''}`.trim();
  }
  return labels;
}
