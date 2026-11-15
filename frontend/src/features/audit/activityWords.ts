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

/** "Done", "Failed", or "Refused: <the words the person saw>". */
export function outcomeWords(line: { outcome: Outcome; reason: string | null }): string {
  if (line.outcome === 'DONE') return 'Done';
  if (line.outcome === 'FAILED') return 'Failed';
  return line.reason?.trim() ? `Refused: ${line.reason.trim()}` : 'Refused';
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
