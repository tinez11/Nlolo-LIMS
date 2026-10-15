import { Link } from 'react-router-dom';
import type { ActivityItem } from '@/api/dashboard';
import { Panel } from '@/components/Panel';
import { formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { describeActivity } from './activity';

/**
 * This module's latest events (2026-10-14), from the audit log. It records what happened and when -- not who: the log
 * carries no actor, and the subtitle says so rather than letting the feed pass for an audit trail of people.
 */
export function ActivityFeed({ items }: { items: ActivityItem[] }) {
  return (
    <Panel title="Recent activity" subtitle="Newest first. The log records what happened, not who did it.">
      {items.length === 0 ? (
        <p className="px-4 pb-4 text-xs text-muted-foreground">Nothing recorded yet.</p>
      ) : (
        <ul className="divide-y divide-border">
          {items.map((item, i) => {
            const { text, to } = describeActivity(item);
            return (
              <li key={`${item.occurredAt}-${i}`} className="flex items-baseline gap-3 px-4 py-2.5 text-sm">
                <time dateTime={item.occurredAt} className="w-36 shrink-0 text-xs text-subtle-foreground">
                  {formatInstant(item.occurredAt)}
                </time>
                <span className="min-w-0 flex-1">
                  {to ? (
                    <Link to={to} className="hover:underline">
                      {text}
                    </Link>
                  ) : (
                    text
                  )}
                  {item.refs.policyNumber && (
                    <span className="ml-2 font-mono text-xs text-muted-foreground">{item.refs.policyNumber}</span>
                  )}
                </span>
                {item.amount && <span className="shrink-0 whitespace-nowrap text-right">{formatMoney(item.amount)}</span>}
              </li>
            );
          })}
        </ul>
      )}
    </Panel>
  );
}
