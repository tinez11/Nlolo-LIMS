import { Panel } from '@/components/Panel';
import { RETURN_CATEGORY_LABELS, type CaseEvent } from '@/api/proposalAmendments';
import { StatusBadge } from '@/components/StatusBadge';
import { formatInstant } from '@/lib/dates';

/**
 * Every return, amendment and resubmission on a proposal, oldest first (2026-10-20) -- what the underwriter asked,
 * what the agent changed (old value and new), and each time it came back. The same panel on both sides, so the
 * underwriter and the agent read one record. Renders nothing for a proposal never returned or postponed: a panel
 * appears when there is something to say.
 */
export function ProposalHistoryPanel({ events }: { events: CaseEvent[] }) {
  if (events.length === 0) return null;
  return (
    <Panel title="Proposal history" subtitle="Each return, correction and resubmission, oldest first.">
      <ol className="divide-y divide-border">
        {events.map((e) => (
          <li key={e.eventId} className="space-y-1.5 px-4 py-3 text-sm">
            <p className="flex flex-wrap items-baseline gap-x-2">
              <span className="font-medium">{HEADINGS[e.kind]}</span>
              <span className="text-xs text-muted-foreground">
                Round {e.round} · {formatInstant(e.at)}
              </span>
            </p>
            {e.reasons.length > 0 && (
              <ul className="space-y-1.5">
                {e.reasons.map((r, i) => (
                  <li key={i} className="text-xs">
                    <p className="font-medium">{RETURN_CATEGORY_LABELS[r.category]}</p>
                    <p>
                      <span className="text-muted-foreground">What is wrong: </span>
                      {r.problem}
                    </p>
                    <p>
                      <span className="text-muted-foreground">What to change: </span>
                      {r.change}
                    </p>
                  </li>
                ))}
              </ul>
            )}
            {e.changes.length > 0 && (
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <caption className="sr-only">What changed</caption>
                  <thead>
                    <tr className="text-left text-muted-foreground">
                      <th className="py-1 pr-3 font-medium">Field</th>
                      <th className="py-1 pr-3 font-medium">Was</th>
                      <th className="py-1 font-medium">Now</th>
                    </tr>
                  </thead>
                  <tbody>
                    {e.changes.map((c, i) => (
                      <tr key={i} className="border-t border-border">
                        <td className="py-1 pr-3">{c.field}</td>
                        <td className="py-1 pr-3">{c.before ?? '—'}</td>
                        <td className="py-1">{c.after ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {e.previousDecision && (
              <p className="text-xs">
                <span className="text-muted-foreground">Answering: </span>
                <StatusBadge kind="underwritingDecision" value={e.previousDecision.outcome} />{' '}
                {e.previousDecision.reason}
              </p>
            )}
            {e.note && <p className="text-xs text-muted-foreground">“{e.note}”</p>}
          </li>
        ))}
      </ol>
    </Panel>
  );
}

const HEADINGS: Record<CaseEvent['kind'], string> = {
  RETURNED: 'Returned to the agent',
  AMENDED: 'Corrected by the agent',
  RESUBMITTED: 'Resubmitted by the agent',
};
