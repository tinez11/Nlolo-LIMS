import { useEffect, useState } from 'react';
import { getStaffNames } from '@/api/access';
import { cn } from '@/lib/cn';

/**
 * Resolves a staff member's token subject to their name, for "reviewed by", "proposed by", "approved by" (2026-10-27).
 *
 * The platform records who acted by subject, and screens printed it raw: "Reviewed by 1bccfb0a-7735-…". PartyName's
 * rules apply: the subject is kept on the title, the raw value shows while loading and when nobody is found, and
 * nothing is invented. A value that is not a subject at all -- an older row that stored a username, "system" -- is
 * shown as it is.
 *
 * Lookups are batched: every StaffName mounted in the same tick asks in one request, so a list of fifty rows naming
 * the same three people costs one call, not fifty.
 */
const NAMES = new Map<string, string | null>();
const QUEUED = new Set<string>();
const LISTENERS = new Set<() => void>();
let flushTimer: ReturnType<typeof setTimeout> | null = null;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function flush() {
  flushTimer = null;
  const ids = [...QUEUED];
  QUEUED.clear();
  for (let i = 0; i < ids.length; i += 100) {
    const batch = ids.slice(i, i + 100);
    void getStaffNames(batch)
      .then((found) => {
        const byId = new Map(found.map((f) => [f.subject.toLowerCase(), f.name]));
        for (const id of batch) NAMES.set(id, byId.get(id.toLowerCase()) ?? null);
      })
      .catch(() => {
        for (const id of batch) NAMES.set(id, null);
      })
      .finally(() => LISTENERS.forEach((l) => l()));
  }
}

function request(subject: string) {
  if (NAMES.has(subject) || QUEUED.has(subject)) return;
  QUEUED.add(subject);
  flushTimer ??= setTimeout(flush, 0);
}

/** Test hook: the cache is module state, and a test must not read another's answers. */
// eslint-disable-next-line react-refresh/only-export-components
export function __clearStaffNameCache() {
  NAMES.clear();
  QUEUED.clear();
}

/** The name for a subject, or the subject itself until (or unless) the platform knows them. */
// eslint-disable-next-line react-refresh/only-export-components
export function useStaffName(subject: string | null | undefined): string | null {
  const [, bump] = useState(0);
  const lookable = !!subject && UUID.test(subject);
  useEffect(() => {
    if (!lookable || !subject || NAMES.has(subject)) return;
    const listener = () => bump((n) => n + 1);
    LISTENERS.add(listener);
    request(subject);
    return () => {
      LISTENERS.delete(listener);
    };
  }, [subject, lookable]);
  if (!subject) return null;
  return (lookable ? NAMES.get(subject) : null) ?? subject;
}

export function StaffName({ subject, className }: { subject: string | null | undefined; className?: string }) {
  const name = useStaffName(subject);
  if (!subject || !name) return <span className={className}>—</span>;
  const resolved = name !== subject;
  return (
    <span className={cn(!resolved && UUID.test(subject) && 'font-mono text-xs', className)} title={subject}>
      {name}
    </span>
  );
}
