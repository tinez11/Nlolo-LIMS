import { z } from 'zod';
import type { ProductCategory, ProductVersionSpec } from '@/api/types';

/**
 * A FUNERAL version's terms as the publish form holds them (family funeral cover), and the rules of
 * `FuneralPlanValidator` the console can check before sending, in its words. The server checks the rest.
 *
 * The premium table is a pasted grid -- `plan,role,ageFrom,ageTo,yearlyPremium`, one row per line, an
 * optional header -- for annuitySchema's reason: two plans, five roles and a handful of age bands is
 * dozens of rows nobody types into boxes one by one.
 */

export const FUNERAL_ROLES = ['MAIN_MEMBER', 'SPOUSE', 'CHILD', 'PARENT', 'EXTENDED'] as const;
export type FuneralRoleName = (typeof FUNERAL_ROLES)[number];

export const FUNERAL_ROLE_LABELS: Record<FuneralRoleName, string> = {
  MAIN_MEMBER: 'Main member',
  SPOUSE: 'Spouse',
  CHILD: 'Child',
  PARENT: 'Parent',
  EXTENDED: 'Extended family',
};

const ROLE_PLURALS: Record<FuneralRoleName, string> = {
  MAIN_MEMBER: 'main members',
  SPOUSE: 'spouses',
  CHILD: 'children',
  PARENT: 'parents',
  EXTENDED: 'extended family members',
};

const funeralPlanSchema = z.object({
  planCode: z.string().trim(),
  name: z.string().trim(),
  /** The benefit per role, in FUNERAL_ROLES order; blank = this plan does not cover the role. */
  benefits: z.array(z.string().trim()),
});
export type FuneralPlanValues = z.infer<typeof funeralPlanSchema>;

const funeralRoleSchema = z.object({
  role: z.enum(FUNERAL_ROLES),
  allowed: z.boolean(),
  maxLives: z.string().trim(),
  minEntryAge: z.string().trim(),
  maxEntryAge: z.string().trim(),
  coverStopAge: z.string().trim(),
  studentStopAge: z.string().trim(),
});
export type FuneralRoleValues = z.infer<typeof funeralRoleSchema>;

export const funeralFieldsShape = {
  funeralPlans: z.array(funeralPlanSchema),
  funeralPremiumsText: z.string(),
  funeralRoles: z.array(funeralRoleSchema),
  funeralMaxPricedAge: z.string().trim(),
  funeralWaitingMonths: z.string().trim(),
  funeralAccidentWaives: z.boolean(),
  funeralPayee: z.string().trim(),
  funeralOnMainMemberDeath: z.string().trim(),
  funeralFreeCover: z.boolean(),
};

export interface FuneralFields {
  funeralPlans: FuneralPlanValues[];
  funeralPremiumsText: string;
  funeralRoles: FuneralRoleValues[];
  funeralMaxPricedAge: string;
  funeralWaitingMonths: string;
  funeralAccidentWaives: boolean;
  funeralPayee: string;
  funeralOnMainMemberDeath: string;
  funeralFreeCover: boolean;
}

/** The spec's defaults, which staff edit: one spouse, six children to 21 (25 a student), parents to 75. */
export function blankFuneralFields(): FuneralFields {
  const role = (r: FuneralRoleName, allowed: boolean, maxLives: string, min: string, max: string, stop = '', student = '') =>
    ({ role: r, allowed, maxLives, minEntryAge: min, maxEntryAge: max, coverStopAge: stop, studentStopAge: student });
  return {
    funeralPlans: [],
    funeralPremiumsText: '',
    funeralRoles: [
      role('MAIN_MEMBER', true, '1', '18', '65'),
      role('SPOUSE', true, '1', '18', '65'),
      role('CHILD', true, '6', '0', '20', '21', '25'),
      role('PARENT', true, '4', '18', '75'),
      role('EXTENDED', true, '4', '0', '65'),
    ],
    funeralMaxPricedAge: '100',
    funeralWaitingMonths: '6',
    funeralAccidentWaives: true,
    funeralPayee: '',
    funeralOnMainMemberDeath: '',
    funeralFreeCover: true,
  };
}

export function blankFuneralPlan(): FuneralPlanValues {
  return { planCode: '', name: '', benefits: FUNERAL_ROLES.map(() => '') };
}

export interface ParsedPremium {
  planCode: string;
  role: FuneralRoleName;
  ageFrom: number;
  ageTo: number;
  yearlyPremium: number;
}

/** The pasted premium table; the first line that does not parse is refused by number. */
export function parsePremiums(text: string): { rows: ParsedPremium[]; error?: string } {
  const rows: ParsedPremium[] = [];
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (line === '' || (i === 0 && /^plan\b/i.test(line))) continue;
    const cells = line.split(/[,\t]/).map((c) => c.trim());
    const [planCode, role, from, to, premium] = cells;
    const ageFrom = Number(from);
    const ageTo = Number(to);
    const yearlyPremium = Number(premium);
    if (cells.length !== 5 || !planCode || !(FUNERAL_ROLES as readonly string[]).includes(role)
        || !Number.isInteger(ageFrom) || !Number.isInteger(ageTo) || ageFrom < 0 || ageTo < ageFrom
        || !(yearlyPremium > 0)) {
      return { rows, error: `Line ${i + 1} is not plan,role,ageFrom,ageTo,yearlyPremium: "${line}"` };
    }
    rows.push({ planCode, role: role as FuneralRoleName, ageFrom, ageTo, yearlyPremium });
  }
  return { rows };
}

export function validateFuneral(category: ProductCategory, v: FuneralFields, ctx: z.RefinementCtx) {
  if (category !== 'FUNERAL') return;
  const issue = (path: (string | number)[], message: string) => ctx.addIssue({ code: 'custom', path, message });

  if (v.funeralPlans.length === 0) {
    issue(['funeralPlans'], 'A FUNERAL version needs at least one plan');
  }
  const seen = new Set<string>();
  v.funeralPlans.forEach((plan, i) => {
    if (plan.planCode === '') issue(['funeralPlans', i, 'planCode'], 'Every plan needs a code');
    else if (seen.has(plan.planCode)) issue(['funeralPlans', i, 'planCode'], `Plan code ${plan.planCode} appears twice`);
    seen.add(plan.planCode);
    if (plan.name === '') issue(['funeralPlans', i, 'name'], 'Every plan needs a name');
    if (plan.benefits[0] === '') issue(['funeralPlans', i, 'benefits', 0], `Plan ${plan.planCode || i + 1} does not cover the main member`);
    plan.benefits.forEach((b, r) => {
      if (b !== '' && !(Number(b) > 0)) issue(['funeralPlans', i, 'benefits', r], 'A benefit is an amount above zero');
      if (b !== '' && !v.funeralRoles[r]?.allowed) {
        issue(['funeralPlans', i, 'benefits', r],
          `Plan ${plan.planCode || i + 1} covers ${ROLE_PLURALS[FUNERAL_ROLES[r]]}, but ${FUNERAL_ROLES[r]} is not allowed`);
      }
    });
  });

  v.funeralRoles.forEach((r, i) => {
    if (!r.allowed) return;
    const min = Number(r.minEntryAge);
    const max = Number(r.maxEntryAge);
    if (r.minEntryAge === '' || r.maxEntryAge === '' || !(max >= min)) {
      issue(['funeralRoles', i, 'maxEntryAge'], 'An entry age range, youngest first');
    }
    if (r.coverStopAge !== '' && !(Number(r.coverStopAge) > max)) {
      issue(['funeralRoles', i, 'coverStopAge'], `Cover must stop after the oldest entry age, ${r.maxEntryAge}`);
    }
    if (r.studentStopAge !== '' && (r.role !== 'CHILD' || !(Number(r.studentStopAge) > Number(r.coverStopAge)))) {
      issue(['funeralRoles', i, 'studentStopAge'],
        'Only a child’s cover can extend for a student, and to an age after it would otherwise stop');
    }
  });

  if (v.funeralWaitingMonths !== '' && !(Number(v.funeralWaitingMonths) > 0)) {
    issue(['funeralWaitingMonths'], 'A waiting period is a number of months above zero; leave it empty for none');
  }
  if (v.funeralPayee === '') issue(['funeralPayee'], 'Choose who is paid when a dependant dies');
  if (v.funeralOnMainMemberDeath === '') issue(['funeralOnMainMemberDeath'], 'Choose what happens when the main member dies');

  const { rows, error } = parsePremiums(v.funeralPremiumsText);
  if (error) {
    issue(['funeralPremiumsText'], error);
    return;
  }
  // FuneralPlanValidator's coverage rule: every age a covered role can reach is priced exactly once.
  const maxPriced = Number(v.funeralMaxPricedAge);
  for (const plan of v.funeralPlans) {
    for (let r = 0; r < FUNERAL_ROLES.length; r++) {
      const role = FUNERAL_ROLES[r];
      const rule = v.funeralRoles[r];
      if (plan.benefits[r] === '' || !rule?.allowed) continue;
      const bands = rows.filter((p) => p.planCode === plan.planCode && p.role === role).sort((a, b) => a.ageFrom - b.ageFrom);
      for (let k = 1; k < bands.length; k++) {
        if (bands[k].ageFrom <= bands[k - 1].ageTo) {
          issue(['funeralPremiumsText'], `Plan ${plan.planCode}, ${role}: ages ${bands[k - 1].ageFrom}-${bands[k - 1].ageTo} and ${bands[k].ageFrom}-${bands[k].ageTo} overlap`);
          return;
        }
      }
      const last = rule.coverStopAge !== ''
        ? Math.max(Number(rule.coverStopAge), rule.studentStopAge !== '' ? Number(rule.studentStopAge) : 0) - 1
        : maxPriced;
      for (let age = Number(rule.minEntryAge); age <= last; age++) {
        if (!bands.some((b) => b.ageFrom <= age && age <= b.ageTo)) {
          issue(['funeralPremiumsText'], `Plan ${plan.planCode}, ${role}: no premium for age ${age}`);
          return;
        }
      }
    }
  }
}

export function toFuneralRequest(v: FuneralFields): NonNullable<ProductVersionSpec['funeral']> {
  const num = (s: string) => (s === '' ? null : Number(s));
  return {
    plans: v.funeralPlans.map((p) => ({ planCode: p.planCode, name: p.name })),
    benefits: v.funeralPlans.flatMap((p) =>
      FUNERAL_ROLES.flatMap((role, r) => (p.benefits[r] === '' ? [] : [{ planCode: p.planCode, role, benefit: Number(p.benefits[r]) }]))),
    premiums: parsePremiums(v.funeralPremiumsText).rows,
    roles: v.funeralRoles.filter((r) => r.allowed).map((r) => ({
      role: r.role,
      maxLives: num(r.maxLives),
      minEntryAge: num(r.minEntryAge),
      maxEntryAge: num(r.maxEntryAge),
      coverStopAge: num(r.coverStopAge),
      studentStopAge: num(r.studentStopAge),
    })),
    maxPricedAge: num(v.funeralMaxPricedAge),
    waitingPeriodMonths: num(v.funeralWaitingMonths),
    accidentWaivesWaiting: v.funeralAccidentWaives,
    dependantClaimPayee: (v.funeralPayee || null) as 'MAIN_MEMBER' | 'MAIN_MEMBER_BENEFICIARY' | null,
    onMainMemberDeath: (v.funeralOnMainMemberDeath || null) as 'POLICY_ENDS' | 'SPOUSE_TAKES_OVER' | null,
    freeCoverToPaidDate: v.funeralFreeCover,
  };
}
