import { z } from 'zod';
import type { ProductCategory, ProductVersionSpec } from '@/api/types';

/**
 * A UNIT_LINKED version's terms as the publish form holds them (product step 6), and the rules of
 * `UnitLinkedPlanValidator` the console can check before sending, in its words. The server checks the
 * rest -- that each fund is open, in the register and in the product's currency, and that the mortality
 * table starts at the version's minimum entry age.
 *
 * The allocation bands and the mortality table are pasted grids, for funeralSchema's reason: a mortality
 * table by sex is dozens of rows nobody types into boxes one by one.
 */

export const UL_FREQUENCIES = ['MONTHLY', 'QUARTERLY', 'ANNUALLY', 'SINGLE'] as const;
export type UlFrequency = (typeof UL_FREQUENCIES)[number];

export const UL_FREQUENCY_LABELS: Record<UlFrequency, string> = {
  MONTHLY: 'Monthly',
  QUARTERLY: 'Quarterly',
  ANNUALLY: 'Annually',
  SINGLE: 'Single premium',
};

export const unitLinkedFieldsShape = {
  ulFundCodes: z.array(z.string()),
  ulAllocationText: z.string(),
  ulPolicyFee: z.string().trim(),
  ulMortalityBasis: z.string().trim(),
  ulMortalityText: z.string(),
  ulDeathRule: z.string().trim(),
  ulLapseRule: z.string().trim(),
  ulMinimumPremiumYears: z.string().trim(),
  ulMinimumSurrenderYears: z.string().trim(),
  ulLowFundMonths: z.string().trim(),
  /** The least premium per frequency, in UL_FREQUENCIES order; blank = not offered. */
  ulMinimums: z.array(z.string().trim()),
  ulMultipleMin: z.string().trim(),
  ulMultipleMax: z.string().trim(),
  // U2 options: each feature is offered only when its pair is filled in; all blank = none offered.
  ulFreeSwitches: z.string().trim(),
  ulSwitchFee: z.string().trim(),
  ulMinWithdrawal: z.string().trim(),
  ulMinRemaining: z.string().trim(),
  ulWithdrawalCutsCover: z.boolean(),
  ulTopUpPercent: z.string().trim(),
  ulMinTopUp: z.string().trim(),
  /** fromYear,toYear,percent per line, as the allocation bands. */
  ulSurrenderText: z.string(),
};

export interface UnitLinkedFields {
  ulFundCodes: string[];
  ulAllocationText: string;
  ulPolicyFee: string;
  ulMortalityBasis: string;
  ulMortalityText: string;
  ulDeathRule: string;
  ulLapseRule: string;
  ulMinimumPremiumYears: string;
  ulMinimumSurrenderYears: string;
  ulLowFundMonths: string;
  ulMinimums: string[];
  ulMultipleMin: string;
  ulMultipleMax: string;
  ulFreeSwitches: string;
  ulSwitchFee: string;
  ulMinWithdrawal: string;
  ulMinRemaining: string;
  ulWithdrawalCutsCover: boolean;
  ulTopUpPercent: string;
  ulMinTopUp: string;
  ulSurrenderText: string;
}

/** Death pays the higher of sum assured and fund value, and lapse is on exhaustion: the user's defaults. */
export function blankUnitLinkedFields(): UnitLinkedFields {
  return {
    ulFundCodes: [],
    ulAllocationText: '',
    ulPolicyFee: '',
    ulMortalityBasis: '',
    ulMortalityText: '',
    ulDeathRule: 'HIGHER_OF',
    ulLapseRule: 'EXHAUSTION',
    ulMinimumPremiumYears: '',
    ulMinimumSurrenderYears: '',
    ulLowFundMonths: '',
    ulMinimums: UL_FREQUENCIES.map(() => ''),
    ulMultipleMin: '',
    ulMultipleMax: '',
    ulFreeSwitches: '',
    ulSwitchFee: '',
    ulMinWithdrawal: '',
    ulMinRemaining: '',
    ulWithdrawalCutsCover: false,
    ulTopUpPercent: '',
    ulMinTopUp: '',
    ulSurrenderText: '',
  };
}

export interface ParsedBand {
  fromYear: number;
  toYear: number | null;
  percent: number;
}

export interface ParsedMortality {
  ageFrom: number;
  ageTo: number | null;
  sex: 'FEMALE' | 'MALE' | null;
  annualRatePerMille: number;
}

const skip = (line: string, i: number, header: RegExp) => line === '' || (i === 0 && header.test(line));
const optionalInt = (s: string | undefined) => (s === undefined || s === '' ? null : Number(s));

/** `fromYear,toYear,percent` per line; an empty toYear is open-ended. */
export function parseAllocation(text: string): { rows: ParsedBand[]; error?: string } {
  const rows: ParsedBand[] = [];
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (skip(line, i, /^from/i)) continue;
    const cells = line.split(/[,\t]/).map((c) => c.trim());
    const fromYear = Number(cells[0]);
    const toYear = optionalInt(cells[1]);
    const percent = Number(cells[2]);
    if (cells.length !== 3 || !Number.isInteger(fromYear) || (toYear !== null && !Number.isInteger(toYear)) || cells[2] === ''
        || Number.isNaN(percent)) {
      return { rows, error: `Line ${i + 1} is not fromYear,toYear,percent: "${line}"` };
    }
    rows.push({ fromYear, toYear, percent });
  }
  return { rows };
}

/** `ageFrom,ageTo,sex,ratePerMille` per line; an empty ageTo is open-ended, an empty sex is unisex. */
export function parseMortality(text: string): { rows: ParsedMortality[]; error?: string } {
  const rows: ParsedMortality[] = [];
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (skip(line, i, /^age/i)) continue;
    const cells = line.split(/[,\t]/).map((c) => c.trim());
    const ageFrom = Number(cells[0]);
    const ageTo = optionalInt(cells[1]);
    const sex = (cells[2] ?? '').toUpperCase();
    const rate = Number(cells[3]);
    if (cells.length !== 4 || !Number.isInteger(ageFrom) || (ageTo !== null && !Number.isInteger(ageTo))
        || !['', 'FEMALE', 'MALE'].includes(sex) || cells[3] === '' || Number.isNaN(rate)) {
      return { rows, error: `Line ${i + 1} is not ageFrom,ageTo,sex,ratePerMille: "${line}"` };
    }
    rows.push({ ageFrom, ageTo, sex: sex === '' ? null : (sex as 'FEMALE' | 'MALE'), annualRatePerMille: rate });
  }
  return { rows };
}

function checkMortalitySeries(rows: ParsedMortality[], which: string): string | undefined {
  const sorted = [...rows].sort((a, b) => a.ageFrom - b.ageFrom);
  for (let i = 0; i < sorted.length; i++) {
    const r = sorted[i];
    if (r.ageTo === null) {
      if (i !== sorted.length - 1) return `Only the last mortality band${which} may be open-ended`;
    } else if (i === sorted.length - 1) {
      return `The last mortality band${which} must be open-ended (a whole-of-life policy has no maximum age)`;
    } else if (sorted[i + 1].ageFrom !== r.ageTo + 1) {
      return `Mortality bands${which} must run on without gaps or overlaps; age ${r.ageTo} is followed by ${sorted[i + 1].ageFrom}`;
    }
  }
  return undefined;
}

export function validateUnitLinked(category: ProductCategory, v: UnitLinkedFields, ctx: z.RefinementCtx) {
  if (category !== 'UNIT_LINKED') return;
  const issue = (path: (string | number)[], message: string) => ctx.addIssue({ code: 'custom', path, message });
  const between = (s: string, lo: number, hi: number) => s !== '' && Number.isInteger(Number(s)) && Number(s) >= lo && Number(s) <= hi;

  if (v.ulFundCodes.length === 0) issue(['ulFundCodes'], 'A UNIT_LINKED version offers at least one fund');

  if (v.ulPolicyFee === '' || !(Number(v.ulPolicyFee) >= 0)) issue(['ulPolicyFee'], 'A monthly policy fee of zero or more is required');
  if (v.ulDeathRule === '') issue(['ulDeathRule'], 'A UNIT_LINKED version says what death pays: the higher of sum assured and fund value, or both');
  if (v.ulMinimumPremiumYears !== '' && !between(v.ulMinimumPremiumYears, 1, 50)) {
    issue(['ulMinimumPremiumYears'], 'Minimum premium-paying years must be between 1 and 50, or left blank for none');
  }
  if (!between(v.ulMinimumSurrenderYears, 0, 50)) issue(['ulMinimumSurrenderYears'], 'Minimum years before surrender must be between 0 and 50');
  if (!between(v.ulLowFundMonths, 1, 60)) issue(['ulLowFundMonths'], 'The low-fund warning must be between 1 and 60 months of charges');

  const allocation = parseAllocation(v.ulAllocationText);
  if (allocation.error) issue(['ulAllocationText'], allocation.error);
  else if (allocation.rows.length === 0) issue(['ulAllocationText'], 'A UNIT_LINKED version needs at least one allocation band');
  else {
    const bands = allocation.rows;
    for (let i = 0; i < bands.length; i++) {
      const b = bands[i];
      const expected = i === 0 ? 1 : (bands[i - 1].toYear ?? Number.NaN) + 1;
      if (!(b.percent > 0 && b.percent <= 100)) {
        issue(['ulAllocationText'], 'An allocation percent is greater than 0 and at most 100');
        break;
      }
      if (b.fromYear !== expected) {
        issue(['ulAllocationText'], `Allocation bands must run on from year 1 without gaps; band ${i + 1} starts at year ${b.fromYear}`);
        break;
      }
      if (b.toYear === null && i !== bands.length - 1) {
        issue(['ulAllocationText'], 'Only the last allocation band may be open-ended');
        break;
      }
      if (b.toYear !== null && b.toYear < b.fromYear) {
        issue(['ulAllocationText'], `Allocation band ${i + 1} ends before it starts`);
        break;
      }
      if (i === bands.length - 1 && b.toYear !== null) issue(['ulAllocationText'], 'The last allocation band must be open-ended');
    }
  }

  if (v.ulMortalityBasis === '') issue(['ulMortalityBasis'], 'A mortality basis is required: unisex or by sex');
  const mortality = parseMortality(v.ulMortalityText);
  if (mortality.error) issue(['ulMortalityText'], mortality.error);
  else if (mortality.rows.length === 0) {
    issue(['ulMortalityText'], 'A UNIT_LINKED version needs a mortality table to charge the cost of insurance from');
  } else if (mortality.rows.some((r) => r.annualRatePerMille < 0)) {
    issue(['ulMortalityText'], 'A mortality rate per 1,000 is zero or more');
  } else if (v.ulMortalityBasis === 'UNISEX') {
    if (mortality.rows.some((r) => r.sex !== null)) issue(['ulMortalityText'], 'A UNISEX mortality table has no sex on its rows');
    else {
      const e = checkMortalitySeries(mortality.rows, '');
      if (e) issue(['ulMortalityText'], e);
    }
  } else if (v.ulMortalityBasis === 'BY_SEX') {
    const female = mortality.rows.filter((r) => r.sex === 'FEMALE');
    const male = mortality.rows.filter((r) => r.sex === 'MALE');
    if (female.length === 0 || male.length === 0 || female.length + male.length !== mortality.rows.length) {
      issue(['ulMortalityText'], 'A BY_SEX mortality table needs FEMALE and MALE rows for every band');
    } else {
      const e = checkMortalitySeries(female, ' (FEMALE)') ?? checkMortalitySeries(male, ' (MALE)');
      if (e) issue(['ulMortalityText'], e);
    }
  }

  const offered = v.ulMinimums.filter((m) => m !== '');
  if (offered.length === 0) issue(['ulMinimums'], 'A UNIT_LINKED version sets a minimum premium for each frequency it takes');
  v.ulMinimums.forEach((m, i) => {
    if (m !== '' && !(Number(m) > 0)) issue(['ulMinimums', i], 'A minimum premium is greater than zero');
  });

  const min = Number(v.ulMultipleMin);
  const max = Number(v.ulMultipleMax);
  if (v.ulMultipleMin === '' || v.ulMultipleMax === '' || !(min > 0) || !(max > 0)) {
    issue(['ulMultipleMax'], "The sum assured's minimum and maximum multiples of the annual premium are required, and greater than zero");
  } else if (max < min) {
    issue(['ulMultipleMax'], `The sum assured's maximum multiple ${v.ulMultipleMax} is below its minimum ${v.ulMultipleMin}`);
  }

  validateOptions(v, issue);
}

/** The U2 options, in UnitLinkedPlanValidator.checkOptions's words: each feature's pair both or neither. */
function validateOptions(v: UnitLinkedFields, issue: (path: (string | number)[], message: string) => void) {
  const both = (a: string, b: string) => (a === '') === (b === '');
  if (!both(v.ulFreeSwitches, v.ulSwitchFee)) {
    issue(['ulSwitchFee'], 'Switching needs both the free switches per year and the fee for each switch after them');
  } else if (v.ulFreeSwitches !== '' && !(Number.isInteger(Number(v.ulFreeSwitches)) && Number(v.ulFreeSwitches) >= 0 && Number(v.ulSwitchFee) >= 0)) {
    issue(['ulSwitchFee'], 'Free switches and the switch fee are zero or more');
  }
  if (!both(v.ulMinWithdrawal, v.ulMinRemaining)) {
    issue(['ulMinRemaining'], 'Withdrawals need both the minimum withdrawal and the minimum value left in the policy');
  } else if (v.ulMinWithdrawal !== '' && !(Number(v.ulMinWithdrawal) > 0 && Number(v.ulMinRemaining) >= 0)) {
    issue(['ulMinRemaining'], 'A minimum withdrawal is greater than zero and the minimum value left is zero or more');
  }
  if (!both(v.ulTopUpPercent, v.ulMinTopUp)) {
    issue(['ulMinTopUp'], 'Top-ups need both their allocation percent and the minimum top-up');
  } else if (v.ulTopUpPercent !== '' && !(Number(v.ulTopUpPercent) > 0 && Number(v.ulTopUpPercent) <= 100 && Number(v.ulMinTopUp) > 0)) {
    issue(['ulMinTopUp'], 'A top-up allocation percent is greater than 0 and at most 100, and the minimum top-up greater than zero');
  }
  const surrender = parseAllocation(v.ulSurrenderText);
  if (surrender.error) {
    issue(['ulSurrenderText'], surrender.error);
    return;
  }
  const bands = surrender.rows;
  for (let i = 0; i < bands.length; i++) {
    const b = bands[i];
    const expected = i === 0 ? 1 : (bands[i - 1].toYear ?? Number.NaN) + 1;
    if (b.fromYear !== expected) {
      issue(['ulSurrenderText'], `Surrender charge bands must run on from year 1 without gaps; band ${i + 1} starts at year ${b.fromYear}`);
      return;
    }
    if (!(b.percent >= 0 && b.percent <= 100)) {
      issue(['ulSurrenderText'], 'A surrender charge is between 0% and 100%');
      return;
    }
    if (b.toYear !== null && b.toYear < b.fromYear) {
      issue(['ulSurrenderText'], `Surrender charge band ${i + 1} ends before it starts`);
      return;
    }
  }
  if (bands.length > 0 && bands[bands.length - 1].toYear !== null) {
    issue(['ulSurrenderText'], 'The last surrender charge band must be open-ended');
  }
}

/** True when any U2 option is filled in: only then is an options block sent. */
export function hasUnitLinkedOptions(v: UnitLinkedFields): boolean {
  return [v.ulFreeSwitches, v.ulSwitchFee, v.ulMinWithdrawal, v.ulMinRemaining, v.ulTopUpPercent, v.ulMinTopUp,
    v.ulSurrenderText.trim()].some((s) => s !== '') || v.ulWithdrawalCutsCover;
}

export function toUnitLinkedRequest(v: UnitLinkedFields): NonNullable<ProductVersionSpec['unitLinked']> {
  const num = (s: string) => (s === '' ? null : Number(s));
  return {
    fundCodes: v.ulFundCodes,
    allocationBands: parseAllocation(v.ulAllocationText).rows,
    monthlyPolicyFee: num(v.ulPolicyFee),
    mortalityBasis: (v.ulMortalityBasis || null) as 'UNISEX' | 'BY_SEX' | null,
    mortality: parseMortality(v.ulMortalityText).rows,
    deathRule: (v.ulDeathRule || null) as 'HIGHER_OF' | 'SUM_ASSURED_PLUS_FUND' | null,
    lapseRule: (v.ulLapseRule || null) as 'EXHAUSTION' | 'NON_PAYMENT' | null,
    minimumPremiumYears: num(v.ulMinimumPremiumYears),
    minimumSurrenderYears: num(v.ulMinimumSurrenderYears),
    lowFundWarningMonths: num(v.ulLowFundMonths),
    premiumMinimums: UL_FREQUENCIES.flatMap((frequency, i) =>
      v.ulMinimums[i] === '' ? [] : [{ frequency, amount: Number(v.ulMinimums[i]) }]),
    sumAssuredMultipleMin: num(v.ulMultipleMin),
    sumAssuredMultipleMax: num(v.ulMultipleMax),
    ...(hasUnitLinkedOptions(v)
      ? {
          options: {
            freeSwitchesPerYear: num(v.ulFreeSwitches),
            switchFee: num(v.ulSwitchFee),
            minimumWithdrawal: num(v.ulMinWithdrawal),
            minimumRemainingValue: num(v.ulMinRemaining),
            withdrawalReducesSumAssured: v.ulWithdrawalCutsCover,
            topUpAllocationPercent: num(v.ulTopUpPercent),
            minimumTopUp: num(v.ulMinTopUp),
            surrenderCharges: parseAllocation(v.ulSurrenderText).rows,
          },
        }
      : {}),
  };
}
