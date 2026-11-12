/**
 * Where a report cell opens (2026-11-11, analytics plan 2, drill-down). The server marks a column with what it links
 * to and puts the record's id in the row under a hidden "_<key>Id" -- or, for a policy, the cell's text is the number.
 */
export type LinkKind = 'POLICY' | 'CLAIM' | 'CLIENT' | 'AGENT';

const BASE: Record<LinkKind, string> = {
  POLICY: '/staff/policies/',
  CLAIM: '/staff/claims/',
  CLIENT: '/staff/parties/',
  AGENT: '/staff/agents/',
};

/** The path a cell opens, or null when the row names nothing to open (a total row, an empty cell). */
export function linkFor(
  kind: LinkKind | null | undefined,
  key: string,
  row: Record<string, string | undefined>,
): string | null {
  if (!kind) return null;
  if (!row[key]) return null;
  const id = row[`_${key}Id`] ?? (kind === 'POLICY' ? row[key] : undefined);
  if (!id || id === 'Total') return null;
  return BASE[kind] + encodeURIComponent(id);
}
