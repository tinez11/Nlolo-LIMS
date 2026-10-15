import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { KpiRow, type Kpi } from '@/components/KpiCard';
import { Panel } from '@/components/Panel';
import { PartyName } from '@/components/PartyName';
import { ProductName } from '@/components/ProductName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { countValue } from '@/lib/counts';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectUnderwriting, useDashboardStore } from '@/store/dashboardStore';
import { ActivityFeed } from './ActivityFeed';
import { DashboardRail } from './DashboardRail';

/** The underwriter's dashboard (2026-10-14). Counts link to their queue; "awaiting issue" has no list that shows exactly it. */
export function UnderwritingDashboardPanel() {
  const resource = useDashboardStore(selectUnderwriting);
  const load = useDashboardStore((s) => s.load);

  useEffect(() => {
    void load('underwriting');
  }, [load]);

  if (isInitialLoad(resource)) return <LoadingBlock label="Counting cases" />;
  if (resource.status === 'error' && resource.error && resource.data === null) {
    return <ErrorPanel error={resource.error} onRetry={() => void load('underwriting')} />;
  }
  const view = resource.data;
  if (!view) return null;

  const kpis: Kpi[] = [
    {
      label: 'Open cases',
      value: countValue(view.openCases),
      hint: 'awaiting first review',
      to: '/staff/underwriting?status=OPEN',
    },
    {
      label: 'In review',
      value: countValue(view.casesInReview),
      hint: 'assessed, not yet decided',
      to: '/staff/underwriting?status=IN_REVIEW',
    },
    {
      label: 'Accepted, awaiting issue',
      value: countValue(view.casesAwaitingIssue),
      hint: 'decided, no policy yet',
    },
  ];
  const next = view.queue[0];

  return (
    <div className="space-y-5">
      <KpiRow kpis={kpis} />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="space-y-5">
          <Panel emphasis title="Next in your queue" subtitle="Open cases that have waited longest, oldest first.">
            {view.queue.length === 0 ? (
              <p className="px-4 pb-4 text-xs text-muted-foreground">No open cases.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-band text-left text-xs text-muted-foreground">
                    <tr>
                      <th className="px-4 py-2.5 font-medium">Applicant</th>
                      <th className="px-4 py-2.5 font-medium">Product</th>
                      <th className="px-4 py-2.5 text-right font-medium">Sum assured</th>
                      <th className="px-4 py-2.5 font-medium">Status</th>
                      <th className="px-4 py-2.5">
                        <span className="sr-only">Open</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {view.queue.map((c) => (
                      <tr key={c.caseId} className="h-11 border-t border-border first:border-t-0">
                        <td className="px-4">
                          <PartyName partyId={c.applicantPartyId} />
                        </td>
                        <td className="px-4">{c.productId ? <ProductName productId={c.productId} /> : '—'}</td>
                        <td className="px-4 text-right whitespace-nowrap">
                          {c.sumAssured ? formatMoney(c.sumAssured) : '—'}
                        </td>
                        <td className="px-4">
                          <StatusBadge kind="underwritingCase" value={c.status} />
                        </td>
                        <td className="px-4 text-right">
                          <Link
                            to={`/staff/underwriting/${c.caseId}`}
                            aria-label={`Review ${c.caseId}`}
                            className="rounded-md px-2 py-1 text-sm font-medium hover:bg-hover"
                          >
                            Review
                          </Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
          <ActivityFeed items={view.recentActivity} />
        </div>
        <DashboardRail
          alerts={
            view.casesAwaitingIssue
              ? [
                  {
                    tone: 'warning',
                    text: (
                      <>
                        <b>{countValue(view.casesAwaitingIssue)} accepted cases</b> have no policy yet. Issuing is the
                        next step.
                      </>
                    ),
                  },
                ]
              : []
          }
          actions={[
            ...(next
              ? [
                  {
                    label: 'Review the next case',
                    description: 'The open case that has waited longest',
                    to: `/staff/underwriting/${next.caseId}`,
                  },
                ]
              : []),
            { label: 'Open a case', description: 'For a registered client', to: '/staff/underwriting/new' },
            { label: 'Issue a policy', description: 'From an accepted case', to: '/staff/policies/new' },
            { label: 'All open cases', description: 'The full queue', to: '/staff/underwriting?status=OPEN' },
          ]}
          absent={[
            'SLA deadlines on cases',
            'Accepted vs declined, and turnaround — phase 2',
            'A risk-level field to filter by',
          ]}
        />
      </div>
    </div>
  );
}
