import type { ActivityLine as Line } from '@/api/activity';
import { cn } from '@/lib/cn';
import { ActivityLine } from './ActivityLine';
import { byDay, dayWords } from './activityWords';

/**
 * The log as a timeline (2026-11-15 critique): lines cut into days under a heading, each line then carrying only its
 * time. The user asked for "a timeline of things", and a week of full timestamps in one run was a register, not that.
 *
 * Used by the log page and by every record's Activity, so the two read alike. The outer list keeps the name
 * "Activity" (the e2e suite and screen readers find the log by it); each day is an item holding its heading and its
 * own list. `stickyDays` pins the heading under the page bar while its day scrolls by; a record page leaves it off,
 * because its tab strip already holds that spot.
 */
export function ActivityDays({
  lines,
  openId,
  onToggle,
  onPerson,
  showRecord = true,
  stickyDays = false,
  headingLevel = 2,
}: {
  lines: readonly Line[];
  openId: string | null;
  onToggle: (id: string) => void;
  onPerson?: (subject: string) => void;
  showRecord?: boolean;
  stickyDays?: boolean;
  /** 2 on the log page, under its h1; 3 inside a record's panel, under the panel's own heading. */
  headingLevel?: 2 | 3;
}) {
  const Heading = headingLevel === 2 ? 'h2' : 'h3';
  return (
    <ul aria-label="Activity">
      {byDay(lines).map(({ day, first, lines: ofDay }) => (
        <li key={day} className="border-t border-border first:border-t-0">
          <Heading
            className={cn(
              'border-b border-border bg-surface-muted px-4 py-1.5 text-xs font-semibold text-label',
              stickyDays && 'sticky top-[var(--pagebar-h,0px)] z-5',
            )}
          >
            {dayWords(first)}
          </Heading>
          <ul className="divide-y divide-border">
            {ofDay.map((line) => (
              <ActivityLine
                key={line.id}
                line={line}
                open={openId === line.id}
                onToggle={() => onToggle(line.id)}
                showRecord={showRecord}
                {...(onPerson ? { onPerson } : {})}
              />
            ))}
          </ul>
        </li>
      ))}
    </ul>
  );
}
