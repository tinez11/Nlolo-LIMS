import type { PublishVersionFormInput } from './publishVersionSchema';

type Row = PublishVersionFormInput['baseRates'][number];

/**
 * The base rate table as a person writes it: one line per age band, rates across (2026-10-22).
 *
 * The form holds it the way the server takes it -- one row per (band, sex), three smoker columns each -- which on
 * screen was two lines of eight boxes per band. A band here is both of those rows; editing it writes both, so the
 * pair can never drift apart.
 */
export interface Band {
  ageFrom: string;
  ageTo: string;
  termFrom: string;
  termTo: string;
  female: Rates;
  male: Rates;
}

export interface Rates {
  nonSmoker: string;
  smoker: string;
  unknown: string;
}

const NO_RATES: Rates = { nonSmoker: '', smoker: '', unknown: '' };

export function blankBand(ageFrom = '', ageTo = ''): Band {
  return { ageFrom, ageTo, termFrom: '', termTo: '', female: { ...NO_RATES }, male: { ...NO_RATES } };
}

/**
 * Rows to bands. The rows come as the grid writes them -- each band's female row, then its male row -- so a female
 * row starts a band and a male row completes the band before it when the bounds match. Anything else (a male row
 * alone) becomes a band of its own with the other sex blank, rather than being dropped.
 */
export function bandsFromRows(rows: readonly Row[]): Band[] {
  const bands: Band[] = [];
  let awaitingMale: Band | null = null;
  for (const row of rows) {
    const rates = { nonSmoker: row.nonSmoker, smoker: row.smoker, unknown: row.unknown };
    const bounds = {
      ageFrom: row.ageFrom,
      ageTo: row.ageTo,
      termFrom: row.termFromMonths ?? '',
      termTo: row.termToMonths ?? '',
    };
    const sameBounds =
      awaitingMale !== null &&
      awaitingMale.ageFrom === bounds.ageFrom &&
      awaitingMale.ageTo === bounds.ageTo &&
      awaitingMale.termFrom === bounds.termFrom &&
      awaitingMale.termTo === bounds.termTo;
    if (row.sex === 'MALE' && awaitingMale && sameBounds) {
      awaitingMale.male = rates;
      awaitingMale = null;
      continue;
    }
    const band: Band = { ...bounds, female: { ...NO_RATES }, male: { ...NO_RATES } };
    if (row.sex === 'FEMALE') {
      band.female = rates;
      awaitingMale = band;
    } else {
      band.male = rates;
      awaitingMale = null;
    }
    bands.push(band);
  }
  return bands;
}

/** Bands back to the rows the schema and the server take: female then male, per band. */
export function rowsFromBands(bands: readonly Band[]): Row[] {
  return bands.flatMap((b) => {
    const common = { ageFrom: b.ageFrom, ageTo: b.ageTo, termFromMonths: b.termFrom, termToMonths: b.termTo };
    return [
      { ...common, sex: 'FEMALE' as const, ...b.female },
      { ...common, sex: 'MALE' as const, ...b.male },
    ];
  });
}

/** Evenly spaced bands, e.g. 18-22, 23-27 ... up to and including `to`. */
export function generateBands(from: number, to: number, every: number): Band[] {
  const bands: Band[] = [];
  if (!(every >= 1) || !(to >= from)) return bands;
  for (let start = from; start <= to; start += every) {
    bands.push(blankBand(String(start), String(Math.min(start + every - 1, to))));
  }
  return bands;
}

/** The editable cells of a band line, left to right -- the order a pasted spreadsheet fills. */
export function cellOrder(byTerm: boolean, withUnknown: boolean): string[] {
  const rates = (sex: 'female' | 'male') =>
    withUnknown ? [`${sex}.nonSmoker`, `${sex}.smoker`, `${sex}.unknown`] : [`${sex}.nonSmoker`, `${sex}.smoker`];
  return ['ageFrom', 'ageTo', ...(byTerm ? ['termFrom', 'termTo'] : []), ...rates('female'), ...rates('male')];
}

function setCell(band: Band, cell: string, value: string): Band {
  const [head, tail] = cell.split('.') as [string, keyof Rates | undefined];
  if (tail && (head === 'female' || head === 'male')) return { ...band, [head]: { ...band[head], [tail]: value } };
  return { ...band, [head]: value };
}

export function editCell(bands: readonly Band[], row: number, cell: string, value: string): Band[] {
  return bands.map((b, i) => (i === row ? setCell(b, cell, value) : b));
}

/**
 * A block copied from a spreadsheet, written from the cell it was pasted into -- rightwards across the line and
 * downwards across bands, adding bands as it runs out. Commas inside numbers ("1,250.50") are dropped; a blank
 * cell stays blank, which on this table means "not priced", never zero.
 */
export function pasteBlock(bands: readonly Band[], row: number, cell: string, text: string, order: string[]): Band[] {
  const lines = text.replace(/\r/g, '').split('\n').filter((l, i, all) => l.trim() !== '' || i < all.length - 1);
  const start = order.indexOf(cell);
  if (start < 0) return [...bands];
  const next = [...bands];
  lines.forEach((line, i) => {
    const target = row + i;
    while (next.length <= target) next.push(blankBand());
    line.split('\t').forEach((raw, j) => {
      const column = order[start + j];
      if (!column) return;
      next[target] = setCell(next[target]!, column, raw.trim().replace(/,/g, ''));
    });
  });
  return next;
}

/** Whether a paste is a block (several cells) rather than one value typed into one box. */
export function isBlock(text: string): boolean {
  return /[\t\n]/.test(text.trim());
}

/**
 * Gaps and overlaps between consecutive bands of the same term band, said on the band they concern. The server
 * refuses a table that leaves an entry age unpriced; this says where, while it is being typed.
 */
export function bandNotes(bands: readonly Band[]): (string | null)[] {
  const notes: (string | null)[] = bands.map(() => null);
  const groups = new Map<string, number[]>();
  bands.forEach((b, i) => {
    const k = `${b.termFrom}|${b.termTo}`;
    groups.set(k, [...(groups.get(k) ?? []), i]);
  });
  for (const indices of groups.values()) {
    const ordered = indices
      .filter((i) => bands[i]!.ageFrom !== '' && bands[i]!.ageTo !== '')
      .sort((a, b) => Number(bands[a]!.ageFrom) - Number(bands[b]!.ageFrom));
    for (let k = 0; k < ordered.length; k++) {
      const band = bands[ordered[k]!]!;
      const from = Number(band.ageFrom);
      const to = Number(band.ageTo);
      if (to < from) {
        notes[ordered[k]!] = 'Ends before it starts';
        continue;
      }
      if (k === 0) continue;
      const prevTo = Number(bands[ordered[k - 1]!]!.ageTo);
      if (from <= prevTo) notes[ordered[k]!] = `Overlaps ages up to ${prevTo}`;
      else if (from > prevTo + 1) notes[ordered[k]!] = `Ages ${prevTo + 1}–${from - 1} not priced`;
    }
  }
  return notes;
}
