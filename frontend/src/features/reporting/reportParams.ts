import type { ReportColumn, ReportDefinition, ReportParams } from '@/api/reporting';
import { isValidAmount } from '@/lib/money';

/** The filter form's state: every field as the string an input holds. Empty means "not set". */
export interface ReportFilterState {
  asOf: string;
  compareAsOf: string;
  from: string;
  to: string;
  cohortFrom: string;
  cohortTo: string;
  groupBy: string;
  clientType: string;
  /** Narrows any report to one product; every report's query applies it. */
  productId: string;
}

const pad = (n: number) => String(n).padStart(2, '0');
/** Local calendar date, not toISOString(): that is UTC, which is yesterday for three hours every night in Dar. */
const isoDate = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const isoMonth = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}`;

export function defaultFilters(definition: ReportDefinition, today: Date): ReportFilterState {
  const monthStart = new Date(today.getFullYear(), today.getMonth(), 1);
  const cohort = new Date(today.getFullYear() - 2, today.getMonth() - 1, 1);
  const kind = definition.dateFilterKind;
  return {
    asOf: kind === 'AS_OF' || kind === 'AS_OF_AND_PERIOD' ? isoDate(today) : '',
    compareAsOf: '',
    from: kind === 'PERIOD' || kind === 'AS_OF_AND_PERIOD' ? isoDate(monthStart) : '',
    to: kind === 'PERIOD' || kind === 'AS_OF_AND_PERIOD' ? isoDate(today) : '',
    cohortFrom: kind === 'COHORT' ? isoMonth(cohort) : '',
    cohortTo: kind === 'COHORT' ? isoMonth(cohort) : '',
    groupBy: '',
    clientType: '',
    productId: '',
  };
}

const FIELDS: (keyof ReportFilterState)[] = ['asOf', 'compareAsOf', 'from', 'to', 'cohortFrom', 'cohortTo', 'groupBy', 'clientType', 'productId'];

export function stateFromSearch(definition: ReportDefinition, search: URLSearchParams, today: Date): ReportFilterState {
  const state = defaultFilters(definition, today);
  for (const field of FIELDS) {
    const value = search.get(field);
    if (value) state[field] = value;
  }
  return state;
}

/** The query string the backend expects: only the date fields this report's kind takes (the server refuses
 * a stray one), plus grouping and client type when chosen. */
export function toParams(definition: ReportDefinition, state: ReportFilterState): ReportParams {
  const kind = definition.dateFilterKind;
  const params: ReportParams = {};
  const put = (field: keyof ReportFilterState) => {
    if (state[field]) params[field] = state[field];
  };
  if (kind === 'AS_OF' || kind === 'AS_OF_AND_PERIOD') put('asOf');
  if (kind === 'AS_OF' && definition.comparable) put('compareAsOf');
  if (kind === 'PERIOD' || kind === 'AS_OF_AND_PERIOD') {
    put('from');
    put('to');
  }
  if (kind === 'COHORT') {
    put('cohortFrom');
    put('cohortTo');
  }
  if (definition.groupByOptions.length > 0) put('groupBy');
  put('clientType');
  put('productId');
  return params;
}

export function validateFilters(definition: ReportDefinition, state: ReportFilterState): string | null {
  const kind = definition.dateFilterKind;
  if ((kind === 'AS_OF' || kind === 'AS_OF_AND_PERIOD') && !state.asOf) return 'Choose an As of date.';
  if (kind === 'PERIOD' || kind === 'AS_OF_AND_PERIOD') {
    if (!state.from || !state.to) return 'Choose both a From and a To date.';
    if (state.from > state.to) return 'From must not be after To.';
  }
  if (kind === 'COHORT') {
    if (!state.cohortFrom || !state.cohortTo) return 'Choose the first and last activation month.';
    if (state.cohortFrom > state.cohortTo) return 'The first month must not be after the last.';
  }
  return null;
}

const GROUPED_2DP = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: true });
const GROUPED_0DP = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0, useGrouping: true });

/** Display only: grouping and a % sign. A value that is not a number is shown as it came, so a backend
 * defect is visible rather than coerced into a plausible figure. */
export function formatCell(type: ReportColumn['type'], value: string | undefined): string {
  if (value === undefined || value === '') return '—';
  if (type === 'TEXT' || type === 'DATE') return value;
  if (!isValidAmount(value)) return value;
  if (type === 'MONEY') return GROUPED_2DP.format(value);
  if (type === 'COUNT') return GROUPED_0DP.format(value);
  return `${value}%`;
}

/**
 * How many rows of one section the console draws. A summary is a handful of rows; a policy register is one row
 * per policy -- 1,657 on the dev book -- and drawing every one stalls the page for no reader's benefit. The
 * download still carries every row: the server never caps.
 */
export const SECTION_ROW_LIMIT = 200;

/** The rows to draw, and how many were left for the download. */
export function capRows<T>(rows: readonly T[], limit: number = SECTION_ROW_LIMIT): { shown: T[]; hidden: number } {
  return rows.length <= limit ? { shown: [...rows], hidden: 0 } : { shown: rows.slice(0, limit), hidden: rows.length - limit };
}
