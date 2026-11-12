import type { ReportDefinition } from '@/api/reporting';
import type { ReportFilterState } from './reportParams';

/**
 * The period choices a report offers (2026-11-11, analytics plan 2; the user: "more filters on reports, including
 * quarters"; mockup screen 3). Each sets the dates or months the report already takes -- the server is unchanged.
 * Quarters are calendar quarters. A choice never ends after today: a report cannot show tomorrow.
 */
export interface PeriodPreset {
  id: string;
  label: string;
  set: Partial<ReportFilterState>;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const pad = (n: number) => String(n).padStart(2, '0');
/** Local calendar date, never toISOString (UTC: yesterday for three hours every night in Dar). */
const day = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const month = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}`;
const lastOfMonth = (year: number, m: number) => new Date(year, m + 1, 0);
const earlier = (a: Date, b: Date) => (a.getTime() <= b.getTime() ? a : b);

interface Span {
  from: Date;
  to: Date;
}

function spans(today: Date) {
  const y = today.getFullYear();
  const m = today.getMonth();
  const q = Math.floor(m / 3) * 3;
  const lastQ = q - 3;
  const lastQYear = lastQ < 0 ? y - 1 : y;
  const lastQStart = (lastQ + 12) % 12;
  return {
    month: { from: new Date(y, m, 1), to: today } as Span,
    lastMonth: { from: new Date(y, m - 1, 1), to: lastOfMonth(y, m - 1) } as Span,
    quarter: { from: new Date(y, q, 1), to: earlier(lastOfMonth(y, q + 2), today) } as Span,
    lastQuarter: { from: new Date(lastQYear, lastQStart, 1), to: lastOfMonth(lastQYear, lastQStart + 2) } as Span,
    year: { from: new Date(y, 0, 1), to: today } as Span,
    quarterWords: `${MONTHS[q]}–${MONTHS[q + 2]} ${y}`,
    lastQuarterWords: `${MONTHS[lastQStart]}–${MONTHS[lastQStart + 2]} ${lastQYear}`,
  };
}

export function periodPresets(kind: ReportDefinition['dateFilterKind'], today: Date): PeriodPreset[] {
  const s = spans(today);
  const ranged: [string, string, Span][] = [
    ['month', 'This month', s.month],
    ['lastMonth', 'Last month', s.lastMonth],
    ['quarter', `This quarter (${s.quarterWords})`, s.quarter],
    ['lastQuarter', `Last quarter (${s.lastQuarterWords})`, s.lastQuarter],
    ['year', 'This year', s.year],
  ];
  switch (kind) {
    case 'PERIOD':
    case 'AS_OF_AND_PERIOD':
      return ranged.map(([id, label, span]) => ({ id, label, set: { from: day(span.from), to: day(span.to) } }));
    case 'MONTH_RANGE':
      return ranged.map(([id, label, span]) => ({ id, label, set: { monthFrom: month(span.from), monthTo: month(span.to) } }));
    case 'MONTH':
      return [
        { id: 'month', label: 'This month', set: { monthFrom: month(s.month.from) } },
        { id: 'lastMonth', label: 'Last month', set: { monthFrom: month(s.lastMonth.from) } },
      ];
    case 'AS_OF': {
      const y = today.getFullYear();
      return [
        { id: 'today', label: 'Today', set: { asOf: day(today) } },
        { id: 'endLastMonth', label: 'End of last month', set: { asOf: day(s.lastMonth.to) } },
        { id: 'endLastQuarter', label: 'End of last quarter', set: { asOf: day(s.lastQuarter.to) } },
        { id: 'endLastYear', label: 'End of last year', set: { asOf: day(new Date(y - 1, 11, 31)) } },
      ];
    }
    default:
      // COHORT: activation months are not a calendar period.
      return [];
  }
}

/** The choice the filters currently match, or null -- dates picked by hand. */
export function activePreset(presets: PeriodPreset[], state: ReportFilterState): string | null {
  const match = presets.find((p) =>
    (Object.keys(p.set) as (keyof ReportFilterState)[]).every((k) => state[k] === p.set[k]),
  );
  return match?.id ?? null;
}
