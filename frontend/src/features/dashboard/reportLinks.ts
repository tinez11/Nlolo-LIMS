/**
 * Where a finance figure came from, as a URL (2026-10-14).
 *
 * Beside the panel rather than inside it, so the component file exports only components -- the same rule that put
 * `countValue` in `lib/counts`.
 */

/**
 * A report's URL with the filter it was read with -- the names `reportParams.stateFromSearch` reads back.
 *
 * @param branch the dashboard's branch choice (2026-11-23, branch views): a code or `all`, carried so the report opens
 *               on the same branch the figure was read for. Omitted, the report applies its own starting rule.
 */
export function reportLink(key: string, params: Record<string, string>, branch?: string): string {
  const withBranch = branch ? { ...params, branch } : params;
  return `/staff/reports/${key}?${new URLSearchParams(withBranch).toString()}`;
}

/** The last civil day of a "YYYY-MM" month, as "YYYY-MM-DD". Calendar arithmetic, not money. */
export function lastDayOf(yyyymm: string): string {
  const [y, m] = yyyymm.split('-').map(Number) as [number, number];
  const day = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return `${yyyymm}-${String(day).padStart(2, '0')}`;
}
