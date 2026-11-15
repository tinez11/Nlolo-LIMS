import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import type { ActivityLine as Line } from '@/api/activity';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { useActivityStore } from '@/store/activityStore';
import { describeActivity } from '@/features/dashboard/activity';
import { actorWords, changeWords, reasonWords, recordPath, timeWords } from './activityWords';

const RECORD_NOUN: Record<string, string> = {
  policy: 'policy',
  claim: 'claim',
  client: 'client',
  agent: 'agent',
  'staff user': 'staff user',
  product: 'product',
  'underwriting case': 'case',
};

/** What a line names: its record's readable name or number, else nothing (an id is never shown as a name). */
function recordWords(line: Line): string | null {
  if (line.recordLabel) return line.recordLabel;
  if (line.recordType === 'policy' && line.recordId) return line.recordId;
  return null;
}

/** A SYSTEM line is an event no person raised: its label is the event type, worded the dashboards' way. */
function labelWords(line: Line): string {
  return line.actorKind === 'SYSTEM'
    ? describeActivity({ eventType: line.label, occurredAt: line.occurredAt, refs: {}, amount: line.amount }).text
    : line.label;
}

/**
 * One line of the activity log, opening below itself (the Messages page's row, 2026-11-14). The row is one button, so
 * the person and record links live in the opened part, never nested inside it.
 */
export function ActivityLine({
  line,
  open,
  onToggle,
  onPerson,
  showRecord = true,
}: {
  line: Line;
  open: boolean;
  onToggle: () => void;
  /** Filter to this line's person; absent where there is no filter (a record's History). */
  onPerson?: (subject: string) => void;
  showRecord?: boolean;
}) {
  const what = recordWords(line);
  const reason = reasonWords(line);
  return (
    <li>
      {/* Time, who, what (and why, when it did not go through), amount, outcome. The line sits under its day, so it
          carries the time alone; the full instant is in `title` and `dateTime` for anyone who needs it. */}
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        className="grid w-full grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 px-4 py-2.5 text-left hover:bg-hover sm:grid-cols-[4.5rem_minmax(0,12rem)_minmax(0,1fr)_8rem_6.5rem]"
      >
        <time
          dateTime={line.occurredAt}
          title={formatInstant(line.occurredAt)}
          className="text-xs tabular-nums text-muted-foreground"
        >
          {timeWords(line.occurredAt)}
        </time>
        <span className="min-w-0 truncate text-right text-sm sm:text-left">{actorWords(line)}</span>
        <span className="col-span-2 min-w-0 sm:col-span-1">
          <span className="block truncate text-sm font-medium">
            {labelWords(line)}
            {showRecord && what && <span className="ml-2 font-normal text-muted-foreground">{what}</span>}
            {line.eventCount > 0 && (
              <span className="ml-2 text-xs font-normal text-muted-foreground">
                · {line.eventCount} {line.eventCount === 1 ? 'thing' : 'things'} followed
              </span>
            )}
          </span>
          {reason && (
            <span className="mt-0.5 block truncate text-xs text-muted-foreground" title={reason}>
              {reason}
            </span>
          )}
        </span>
        <span className="text-sm tabular-nums sm:text-right">{line.amount ? formatMoney(line.amount) : ''}</span>
        <span className="justify-self-end">
          <StatusBadge kind="activityOutcome" value={line.outcome} />
        </span>
      </button>
      {open && <Opened line={line} onPerson={onPerson} />}
    </li>
  );
}

function Opened({ line, onPerson }: { line: Line; onPerson: ((subject: string) => void) | undefined }) {
  const detail = useActivityStore((s) => s.details[line.id]);
  const loadDetail = useActivityStore((s) => s.loadDetail);

  useEffect(() => {
    if (!detail) void loadDetail(line.id);
  }, [detail, line.id, loadDetail]);

  const to = recordPath(line);
  const data = detail?.data;

  return (
    <div className="space-y-3 bg-surface-muted px-4 pb-4 pt-2 text-sm">
      {reasonWords(line) && (
        // In full here: the line itself cuts a long reason to one line.
        <p>
          <span className="text-label">{line.outcome === 'FAILED' ? 'Why it failed:' : 'Why it was refused:'}</span>{' '}
          {reasonWords(line)}
        </p>
      )}

      {detail?.status === 'error' && !data && detail.error && (
        <ErrorPanel error={detail.error} onRetry={() => void loadDetail(line.id)} />
      )}
      {!data && detail?.status !== 'error' && <p className="text-muted-foreground">Loading…</p>}

      {data && data.changes.length > 0 && (
        <section>
          <h3 className="text-xs text-label">What changed</h3>
          <ul className="mt-1 space-y-0.5">
            {data.changes.map((c) => (
              <li key={c.field}>{changeWords(c)}</li>
            ))}
          </ul>
        </section>
      )}

      {data && data.events.length > 0 && (
        <section>
          <h3 className="text-xs text-label">What followed</h3>
          <ul className="mt-1 space-y-0.5">
            {data.events.map((e, i) => {
              const words = describeActivity(e);
              return (
                <li key={`${e.eventType}-${i}`}>
                  {words.to ? <Link to={words.to} className="underline-offset-2 hover:underline">{words.text}</Link> : words.text}
                  <span className="ml-2 text-xs tabular-nums text-muted-foreground">{formatInstant(e.occurredAt)}</span>
                </li>
              );
            })}
          </ul>
        </section>
      )}

      <div className="flex flex-wrap gap-2">
        {to && (
          <Button asChild size="sm" variant="outline">
            <Link to={to}>Open the {RECORD_NOUN[line.recordType ?? ''] ?? 'record'}</Link>
          </Button>
        )}
        {onPerson && line.actorSubject && (
          <Button size="sm" variant="outline" onClick={() => onPerson(line.actorSubject ?? '')}>
            Only {line.actorName?.trim() || 'this person'}
          </Button>
        )}
      </div>

      {data && (
        <details>
          <summary className="cursor-pointer text-xs text-label">Technical detail</summary>
          <dl className="mt-2 grid grid-cols-[7rem_minmax(0,1fr)] gap-x-3 gap-y-1 text-xs">
            <dt className="text-label">Route</dt>
            <dd className="font-mono">{[data.technical.method, data.technical.route].filter(Boolean).join(' ') || '—'}</dd>
            <dt className="text-label">Answer</dt>
            <dd className="font-mono">{data.technical.statusCode ?? '—'}</dd>
          </dl>
          {data.technical.body && (
            <pre className="mt-2 max-h-72 overflow-auto rounded-lg bg-surface px-3 py-2 font-mono text-xs">
              {pretty(data.technical.body)}
            </pre>
          )}
        </details>
      )}
    </div>
  );
}

function pretty(body: string): string {
  try {
    return JSON.stringify(JSON.parse(body), null, 2);
  } catch {
    return body;
  }
}
