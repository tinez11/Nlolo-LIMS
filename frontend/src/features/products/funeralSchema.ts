import { z } from 'zod';
import type { ProductCategory, ProductVersionSpec } from '@/api/types';

/**
 * A FUNERAL version's terms as the publish form holds them (family funeral cover), and the rules of
 * `FuneralPlanValidator` the console can check before sending, in its words. The server checks the rest.
 *
 * The premium table is a grid (2026-10-08): plans down the side, each priced role's age bands across, one
 * yearly premium per box. A role's bands come from its youngest entry age and the ages staff split it at, and
 * end at the last age the role is priced to, so a band can neither gap nor overlap. It replaced a textbox of
 * `plan,role,ageFrom,ageTo,yearlyPremium` lines; those can still be pasted from a spreadsheet to fill the grid.
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
  /** Group funeral schemes: per member per groupRatePeriod, the member's family included; blank on an individual-only version. */
  groupRate: z.string().trim(),
  /** What the group rate is per (2026-10-08): MONTHLY, or YEARLY -- one bill a year, the member list fixed for the year. */
  groupRatePeriod: z.enum(['MONTHLY', 'YEARLY']),
  /** The yearly premium per role and age band, keyed by {@link premiumKey}; blank = not yet priced. */
  premiums: z.record(z.string(), z.string()),
});

/** How a funeral version may be sold (group funeral schemes, 2026-10-07). */
export const FUNERAL_SOLD_AS = ['INDIVIDUAL', 'GROUP', 'BOTH'] as const;
export type FuneralSoldAsName = (typeof FUNERAL_SOLD_AS)[number];
export const FUNERAL_SOLD_AS_LABELS: Record<FuneralSoldAsName, string> = {
  INDIVIDUAL: 'Individual policies',
  GROUP: 'Group schemes',
  BOTH: 'Both',
};
const soldToIndividuals = (s: string) => s !== 'GROUP';
const soldToGroups = (s: string) => s === 'GROUP' || s === 'BOTH';
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
  /** Per role, in FUNERAL_ROLES order: the ages a new price band starts at, e.g. "41, 56"; blank = one band. */
  funeralBandSplits: z.array(z.string()),
  funeralRoles: z.array(funeralRoleSchema),
  funeralMaxPricedAge: z.string().trim(),
  funeralWaitingMonths: z.string().trim(),
  funeralAccidentWaives: z.boolean(),
  funeralPayee: z.string().trim(),
  funeralOnMainMemberDeath: z.string().trim(),
  funeralFreeCover: z.boolean(),
  funeralSoldAs: z.string().trim(),
};

export interface FuneralFields {
  funeralPlans: FuneralPlanValues[];
  funeralBandSplits: string[];
  funeralRoles: FuneralRoleValues[];
  funeralMaxPricedAge: string;
  funeralWaitingMonths: string;
  funeralAccidentWaives: boolean;
  funeralPayee: string;
  funeralOnMainMemberDeath: string;
  funeralFreeCover: boolean;
  funeralSoldAs: string;
}

/** The spec's defaults, which staff edit: one spouse, six children to 21 (25 a student), parents to 75. */
export function blankFuneralFields(): FuneralFields {
  const role = (r: FuneralRoleName, allowed: boolean, maxLives: string, min: string, max: string, stop = '', student = '') =>
    ({ role: r, allowed, maxLives, minEntryAge: min, maxEntryAge: max, coverStopAge: stop, studentStopAge: student });
  return {
    funeralPlans: [],
    funeralBandSplits: FUNERAL_ROLES.map(() => ''),
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
    funeralSoldAs: 'INDIVIDUAL',
  };
}

export function blankFuneralPlan(): FuneralPlanValues {
  return { planCode: '', name: '', benefits: FUNERAL_ROLES.map(() => ''), groupRate: '', groupRatePeriod: 'MONTHLY', premiums: {} };
}

/** One age band of a role's prices: the box in the grid it fills, and the ages it covers. */
export interface PremiumBand {
  key: string;
  from: number;
  to: number;
}

/**
 * Where a band's price is kept on a plan. The first band is keyed by its role alone, so it keeps its price when
 * the role's youngest entry age changes; a later band by the age it starts at. Letters and digits only -- a
 * dot or a bare number would read as a path or an array index to the form.
 */
export function premiumKey(role: FuneralRoleName, from: number | 'first'): string {
  return from === 'first' ? `${role}_first` : `${role}_${from}`;
}

/**
 * The last age a role is priced to, FuneralPlanValidator's rule: the age before cover stops (a student's
 * stop if later), or the version's highest priced age when cover never stops.
 */
export function lastPricedAge(rule: FuneralRoleValues, maxPricedAge: string): number {
  return rule.coverStopAge !== ''
    ? Math.max(Number(rule.coverStopAge), rule.studentStopAge !== '' ? Number(rule.studentStopAge) : 0) - 1
    : Number(maxPricedAge);
}

/**
 * A role's age bands: from its youngest entry age, split at the ages staff typed, to the last priced age.
 * Every split must fall after the youngest entry age and no later than the last priced age, in order.
 */
export function bandsFor(rule: FuneralRoleValues, splits: string, maxPricedAge: string): { bands: PremiumBand[]; error?: string } {
  const min = Number(rule.minEntryAge);
  const last = lastPricedAge(rule, maxPricedAge);
  if (rule.minEntryAge === '' || !Number.isInteger(min) || !Number.isInteger(last) || last < min) return { bands: [] };
  const cells = splits.split(/[\s,]+/).filter((s) => s !== '');
  const ages = cells.map(Number);
  if (ages.some((a) => !Number.isInteger(a))) return { bands: [], error: 'Ages are whole numbers, separated by commas' };
  for (let k = 0; k < ages.length; k++) {
    if (ages[k] <= min || ages[k] > last) {
      return { bands: [], error: `A band starts after the youngest entry age, ${min}, and no later than ${last}` };
    }
    if (k > 0 && ages[k] <= ages[k - 1]) return { bands: [], error: 'List the ages youngest first, each once' };
  }
  const starts = [min, ...ages];
  return {
    bands: starts.map((from, k) => ({
      key: premiumKey(rule.role, k === 0 ? 'first' : from),
      from,
      to: k + 1 < starts.length ? (starts[k + 1] as number) - 1 : last,
    })),
  };
}

/** The roles the grid prices: allowed, and covered by at least one plan. */
export function pricedRoles(v: Pick<FuneralFields, 'funeralPlans' | 'funeralRoles'>): number[] {
  return FUNERAL_ROLES.map((_, r) => r).filter((r) => v.funeralRoles[r]?.allowed
    && v.funeralPlans.some((p) => (p.benefits[r] ?? '') !== ''));
}

/** The grid as the server's premium rows: one per plan, covered role and band, where a price is entered. */
export function premiumRows(v: FuneralFields): ParsedPremium[] {
  const rows: ParsedPremium[] = [];
  for (const r of pricedRoles(v)) {
    const rule = v.funeralRoles[r] as FuneralRoleValues;
    const { bands } = bandsFor(rule, v.funeralBandSplits[r] ?? '', v.funeralMaxPricedAge);
    for (const plan of v.funeralPlans) {
      if ((plan.benefits[r] ?? '') === '') continue;
      for (const band of bands) {
        const price = (plan.premiums[band.key] ?? '').trim();
        if (price !== '' && Number(price) >= 0) {
          rows.push({ planCode: plan.planCode, role: rule.role, ageFrom: band.from, ageTo: band.to, yearlyPremium: Number(price) });
        }
      }
    }
  }
  return rows;
}

/**
 * Rows pasted from a spreadsheet (`plan,role,ageFrom,ageTo,yearlyPremium`) as the grid: each role split where
 * any row starts a band, and each box given the price of the row covering its first age.
 */
export function premiumsFromRows(text: string, v: FuneralFields):
    { splits: string[]; premiums: Record<string, string>[]; error?: string } {
  const { rows, error } = parsePremiums(text);
  const keep = { splits: v.funeralBandSplits, premiums: v.funeralPlans.map((p) => p.premiums) };
  if (error) return { ...keep, error };
  const unknown = rows.find((row) => !v.funeralPlans.some((p) => p.planCode === row.planCode));
  if (unknown) return { ...keep, error: `Plan ${unknown.planCode} is not one of the plans above — add it first` };
  const splits = FUNERAL_ROLES.map((role, r) => {
    const rule = v.funeralRoles[r];
    if (!rule) return '';
    const min = Number(rule.minEntryAge);
    const last = lastPricedAge(rule, v.funeralMaxPricedAge);
    const starts = [...new Set(rows.filter((row) => row.role === role && row.ageFrom > min && row.ageFrom <= last)
      .map((row) => row.ageFrom))];
    return starts.sort((a, b) => a - b).join(', ');
  });
  const premiums = v.funeralPlans.map((plan) => {
    const prices: Record<string, string> = {};
    FUNERAL_ROLES.forEach((role, r) => {
      const rule = v.funeralRoles[r];
      if (!rule) return;
      for (const band of bandsFor(rule, splits[r] ?? '', v.funeralMaxPricedAge).bands) {
        const row = rows.find((x) => x.planCode === plan.planCode && x.role === role && x.ageFrom <= band.from && band.from <= x.ageTo);
        if (row) prices[band.key] = String(row.yearlyPremium);
      }
    });
    return prices;
  });
  return { splits, premiums };
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
        || premium === '' || !(yearlyPremium >= 0)) {
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

  // Group funeral schemes: each plan's rate per member per month when sold to groups, none otherwise.
  v.funeralPlans.forEach((plan, i) => {
    if (soldToGroups(v.funeralSoldAs)) {
      if (!(Number(plan.groupRate) > 0)) {
        issue(['funeralPlans', i, 'groupRate'],
          `Plan ${plan.planCode || i + 1} needs a group rate per member per month above zero: this version is sold to group schemes`);
      }
    } else if (plan.groupRate !== '') {
      issue(['funeralPlans', i, 'groupRate'],
        `Plan ${plan.planCode || i + 1} has a group rate, but this version is sold to individual policies only`);
    }
  });
  // A version sold to group schemes only is priced by its group rates: its grid is not shown and not sent.
  if (!soldToIndividuals(v.funeralSoldAs)) return;

  // FuneralPlanValidator's coverage rule -- every age a covered role can reach is priced exactly once -- holds by
  // construction: a role's bands run from its youngest entry age to its last priced age. What is left is a box
  // with no price, or one that is not a price.
  for (const r of pricedRoles(v)) {
    const rule = v.funeralRoles[r] as FuneralRoleValues;
    const { bands, error } = bandsFor(rule, v.funeralBandSplits[r] ?? '', v.funeralMaxPricedAge);
    if (error) {
      issue(['funeralBandSplits', r], error);
      continue;
    }
    v.funeralPlans.forEach((plan, i) => {
      if ((plan.benefits[r] ?? '') === '') return;
      for (const band of bands) {
        const price = (plan.premiums[band.key] ?? '').trim();
        const where = `Plan ${plan.planCode || i + 1}, ${FUNERAL_ROLE_LABELS[rule.role].toLowerCase()} ${band.from}–${band.to}`;
        if (price === '') issue(['funeralPlans', i, 'premiums', band.key], `${where}: enter the yearly premium`);
        else if (!(Number(price) >= 0)) issue(['funeralPlans', i, 'premiums', band.key], `${where}: a premium is an amount, 0 or more`);
        // A dependant may be included in the main member's premium (0); the main member may not.
        else if (rule.role === 'MAIN_MEMBER' && Number(price) === 0) {
          issue(['funeralPlans', i, 'premiums', band.key], `${where}: the main member's premium must be above zero; nobody is covered free`);
        }
      }
    });
  }
}

export function toFuneralRequest(v: FuneralFields): NonNullable<ProductVersionSpec['funeral']> {
  const num = (s: string) => (s === '' ? null : Number(s));
  return {
    plans: v.funeralPlans.map((p) => ({
      planCode: p.planCode,
      name: p.name,
      groupMonthlyRate: soldToGroups(v.funeralSoldAs) && p.groupRate !== '' ? Number(p.groupRate) : null,
      ...(soldToGroups(v.funeralSoldAs) ? { groupRatePeriod: p.groupRatePeriod } : {}),
    })),
    benefits: v.funeralPlans.flatMap((p) =>
      FUNERAL_ROLES.flatMap((role, r) => (p.benefits[r] === '' ? [] : [{ planCode: p.planCode, role, benefit: Number(p.benefits[r]) }]))),
    premiums: soldToIndividuals(v.funeralSoldAs) ? premiumRows(v) : [],
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
    soldAs: (v.funeralSoldAs || 'INDIVIDUAL') as FuneralSoldAsName,
  };
}
