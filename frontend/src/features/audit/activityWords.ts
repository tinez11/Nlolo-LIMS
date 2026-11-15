/**
 * The activity log's words (2026-11-14): who acted, how it ended, and what changed. Pure, so the page and the History
 * tab say the same thing and the rules are tested once.
 */

export type ActorKind = 'STAFF' | 'AGENT' | 'CUSTOMER' | 'SYSTEM';
export type Outcome = 'DONE' | 'REFUSED' | 'FAILED';

const KIND: Record<Exclude<ActorKind, 'SYSTEM'>, string> = {
  STAFF: 'staff',
  AGENT: 'agent',
  CUSTOMER: 'customer',
};

/** "Asha M. (staff)", or "System" for what the platform did by itself. */
export function actorWords(line: { actorName: string | null; actorKind: ActorKind }): string {
  if (line.actorKind === 'SYSTEM') return 'System';
  return `${line.actorName?.trim() || 'Someone'} (${KIND[line.actorKind]})`;
}

/**
 * The reason a line ended badly, said on the line itself (2026-11-15 critique: an investigator's question is "what
 * was refused or failed, and why", and opening each line to read it was the whole cost of the page). Null for a line
 * that went through, or one the server gave no reason for.
 */
export function reasonWords(line: { outcome: Outcome; reason: string | null }): string | null {
  if (line.outcome === 'DONE') return null;
  return line.reason?.trim() || null;
}

/** The viewer's own calendar day of an instant, as `YYYY-MM-DD` -- the key the log groups its lines by. */
export function dayKey(iso: string): string {
  const d = new Date(iso);
  const month = `${d.getMonth() + 1}`.padStart(2, '0');
  const day = `${d.getDate()}`.padStart(2, '0');
  return `${d.getFullYear()}-${month}-${day}`;
}

/** "Today", "Yesterday", or "Thursday, Nov 12, 2026": a day heading in the log, in the viewer's own timezone. */
export function dayWords(iso: string, now: Date = new Date()): string {
  const key = dayKey(iso);
  if (key === dayKey(now.toISOString())) return 'Today';
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  if (key === dayKey(yesterday.toISOString())) return 'Yesterday';
  return new Intl.DateTimeFormat(undefined, { weekday: 'long', year: 'numeric', month: 'short', day: 'numeric' }).format(
    new Date(iso),
  );
}

/** The time of day alone ("4:31 PM"), for a line that already sits under its day. */
export function timeWords(iso: string): string {
  return new Intl.DateTimeFormat(undefined, { timeStyle: 'short' }).format(new Date(iso));
}

/** Lines, newest first, cut into runs of one day each -- order kept, so a page of the log reads top to bottom. */
export function byDay<T extends { occurredAt: string }>(lines: readonly T[]): { day: string; first: string; lines: T[] }[] {
  const days: { day: string; first: string; lines: T[] }[] = [];
  for (const line of lines) {
    const day = dayKey(line.occurredAt);
    const last = days[days.length - 1];
    if (last && last.day === day) last.lines.push(line);
    else days.push({ day, first: line.occurredAt, lines: [line] });
  }
  return days;
}

/** Where a record opens in the staff console, by the record type the server names it with. */
const RECORD_PATH: Record<string, string> = {
  policy: '/staff/policies/',
  claim: '/staff/claims/',
  client: '/staff/parties/',
  agent: '/staff/agents/',
  'staff user': '/staff/users/',
  product: '/staff/products/',
  'underwriting case': '/staff/underwriting/',
};

export function recordPath(line: { recordType: string | null; recordId: string | null }): string | undefined {
  const base = line.recordType ? RECORD_PATH[line.recordType] : undefined;
  return base && line.recordId ? base + encodeURIComponent(line.recordId) : undefined;
}

/** "phoneNumber" -> "Phone number". */
export function fieldWords(field: string): string {
  const words = field.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/[_-]+/g, ' ').toLowerCase().trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** "Premium: 45000 → 52000", or "Phone number: set to …" when nothing earlier is known. */
export function changeWords(change: { field: string; before: string | null; after: string | null }): string {
  const name = fieldWords(change.field);
  const after = change.after ?? '(cleared)';
  return change.before === null ? `${name}: set to ${after}` : `${name}: ${change.before} → ${after}`;
}
