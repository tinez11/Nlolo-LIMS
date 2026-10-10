import { useAuth } from 'react-oidc-context';
import { Link } from 'react-router-dom';
import { readIdentity } from '@/auth/claims';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, LoadingBlock } from '@/components/states';
import { useWorkWaiting } from '@/navBadges';

/**
 * What is waiting for the person signed in, counted now (2026-10-09, review C5, decision D6).
 *
 * The console used to land a person on the first list their role allows, and the counts that say
 * where the work actually is sat as small badges down the sidebar. This page puts those same
 * counts in one place, in the sidebar's order, each opening its list already filtered to the
 * waiting work. Counts only -- DESIGN.md: there is no analytics endpoint, so no trend, no target,
 * no "up 12%". A count that failed to load is absent rather than zero.
 *
 * Each row's link is a short "Open", described by the row's sentence, rather than the sentence as
 * a link: the sentence names a destination ("3 bank transfers nobody has made yet"), and a link
 * with that name would compete with the sidebar's own link to the same place.
 */
export function TodayPage() {
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const { loading, rows } = useWorkWaiting('staff', identity);
  const waiting = rows.filter((row) => row.count > 0);

  return (
    <>
      <PageHeader
        title="Today"
        description="What is waiting for you, counted now. Each opens the list it came from."
      />
      <div className="px-6 pt-5 pb-8">
        {loading ? (
          <LoadingBlock label="Counting what is waiting" />
        ) : waiting.length === 0 ? (
          <EmptyState
            title="Nothing waiting for you"
            description={
              rows.length === 0
                ? 'None of your queues could be counted just now.'
                : 'Every queue you work is empty.'
            }
          />
        ) : (
          <ul className="max-w-2xl divide-y divide-border rounded-lg border border-border bg-surface">
            {waiting.map((row) => (
              <li key={row.key} className="flex items-center gap-4 px-4 py-3">
                <span className="w-14 shrink-0 text-right text-title tabular-nums">{row.count}</span>
                <span id={`today-${row.key}`} className="min-w-0 flex-1 text-sm">
                  {row.title.replace(/^\d+\s/, '')}
                </span>
                <Link
                  to={`/staff/${row.to}`}
                  aria-describedby={`today-${row.key}`}
                  className="shrink-0 rounded-md px-2.5 py-1 text-sm font-medium hover:bg-hover active:bg-selected active:duration-0"
                >
                  Open
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  );
}
