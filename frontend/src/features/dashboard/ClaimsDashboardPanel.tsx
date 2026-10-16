import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { KpiRow, type Kpi } from '@/components/KpiCard';
import { Panel } from '@/components/Panel';
import { PartyName } from '@/components/PartyName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { countValue } from '@/lib/counts';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectClaims, useDashboardStore } from '@/store/dashboardStore';
import { ActivityFeed } from './ActivityFeed';
import { DashboardRail } from './DashboardRail';

const MONTH_NAME = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

/** "2026-09" -> "September". The month is the server's; this only names it. */
const monthName = (yyyymm: string) => MONTH_NAME[Number(yyyymm.slice(5, 7)) - 1] ?? yyyymm;

const CLAIM_TYPE: Record<string, string> = {
  DEATH: 'Death',
  DISABILITY: 'Disability',
  CRITICAL_ILLNESS: 'Critical illness',
  MATURITY: 'Maturity',
};

/** The claims dashboard (2026-10-14). Every stage is a count of one status; the queue is the reader's own work. */
export function ClaimsDashboardPanel() {
  const resource = useDashboardStore(selectClaims);
  const load = useDashboardStore((s) => s.load);

  useEffect(() => {
    void load('claims');
  }, [load]);

  if (isInitialLoad(resource)) return <LoadingBlock label="Counting claims" />;
  if (resource.status === 'error' && resource.error && resource.data === null) {
    return <ErrorPanel error={resource.error} onRetry={() => void load('claims')} />;
  }
  const view = resource.data;
  if (!view) return null;

  const manager = view.queueStatus === 'SETTLEMENT_REQUESTED';
  const to = (status: string) => `/staff/claims?status=${status}`;
  const kpis: Kpi[] = [
    {
      label: 'Awaiting settlement decision',
      value: countValue(view.settlementRequested),
      hint: 'assessed, recommended',
      to: to('SETTLEMENT_REQUESTED'),
    },
    {
      label: 'Awaiting assessment',
      value: countValue(view.registered),
      hint: 'registered, not yet assessed',
      to: to('REGISTERED'),
    },
    {
      label: 'Approved, not yet paid',
      value: countValue(view.approved),
      hint: 'payout not made',
      to: to('APPROVED'),
    },
    { label: 'Settled, all time', value: countValue(view.settled), hint: 'all time', to: to('SETTLED') },
    {
      label: 'Waiting on a document',
      value: countValue(view.openDocumentRequests),
      hint: 'requests nobody has answered',
    },
    // Last month's benefits, one card per currency. The month is the VIEW's, never the browser's clock.
    ...view.settlements.map((s) => ({
      label: `Benefits paid, ${monthName(s.month)}`,
      value: s.totalApproved ? formatMoney(s.totalApproved) : null,
      hint: `${countValue(s.claims)} ${s.claims === 1 ? 'claim' : 'claims'} settled`,
    })),
  ];
  const next = view.queue[0];
  const stages: [string, number | null, string][] = [
    ['registered, awaiting an assessor', view.registered, 'REGISTERED'],
    ['under assessment', view.underAssessment, 'UNDER_ASSESSMENT'],
    ['awaiting a settlement decision', view.settlementRequested, 'SETTLEMENT_REQUESTED'],
    ['approved, payout not made', view.approved, 'APPROVED'],
    ['reopened', view.reopened, 'REOPENED'],
  ];

  return (
    <div className="space-y-5">
      <KpiRow kpis={kpis} />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="space-y-5">
          <Panel
            emphasis
            title={manager ? 'Awaiting your settlement decision' : 'Awaiting assessment'}
            subtitle={
              manager
                ? 'Oldest first. Approving pays; you cannot decide a claim you assessed.'
                : 'Oldest first. Registered claims nobody has assessed yet.'
            }
          >
            {view.queue.length === 0 ? (
              <p className="px-4 pb-4 text-xs text-muted-foreground">Nothing waiting.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-band text-left text-xs text-muted-foreground">
                    <tr>
                      <th className="px-4 py-2.5 font-medium">Claim</th>
                      <th className="px-4 py-2.5 font-medium">Policy</th>
                      <th className="px-4 py-2.5 font-medium">Claimant</th>
                      <th className="px-4 py-2.5 font-medium">Status</th>
                      <th className="px-4 py-2.5">
                        <span className="sr-only">Open</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {view.queue.map((c) => (
                      <tr key={c.claimId} className="h-11 border-t border-border first:border-t-0">
                        <td className="px-4">
                          {CLAIM_TYPE[c.claimType] ?? c.claimType}
                          {c.dateOfEvent && (
                            <span className="block text-xs text-muted-foreground">
                              event {formatDate(c.dateOfEvent)}
                            </span>
                          )}
                        </td>
                        <td className="px-4 font-mono text-xs">{c.policyNumber}</td>
                        <td className="px-4">
                          <PartyName partyId={c.claimantPartyId} />
                        </td>
                        <td className="px-4">
                          <StatusBadge kind="claim" value={c.status} />
                        </td>
                        <td className="px-4 text-right">
                          <Link
                            to={`/staff/claims/${c.claimId}`}
                            aria-label={`Open ${c.claimId}`}
                            className="rounded-md px-2 py-1 text-sm font-medium hover:bg-hover"
                          >
                            {manager ? 'Decide' : 'Assess'}
                          </Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
          <Panel title="Where claims are" subtitle="Every open claim, by its stage.">
            <ul className="divide-y divide-border">
              {stages.map(([text, n, status]) => (
                <li key={status} className="flex items-center gap-4 px-4 py-2.5">
                  <span className="w-12 shrink-0 text-right text-title">{countValue(n) ?? '—'}</span>
                  <span className="min-w-0 flex-1 text-sm">{text}</span>
                  {n !== null && (
                    <Link
                      to={to(status)}
                      className="rounded-md px-2 py-1 text-sm font-medium hover:bg-hover"
                      aria-label={`Open the ${text} list`}
                    >
                      Open
                    </Link>
                  )}
                </li>
              ))}
            </ul>
          </Panel>
          <ActivityFeed items={view.recentActivity} />
        </div>
        <DashboardRail
          alerts={
            view.approved
              ? [
                  {
                    tone: 'warning',
                    text: (
                      <>
                        <b>{countValue(view.approved)} approved claims</b> have not been paid.
                      </>
                    ),
                  },
                ]
              : []
          }
          actions={[
            { label: 'Register a claim', description: 'Identify the claimant, then the policy', to: '/staff/claims/new' },
            ...(next
              ? [
                  {
                    label: manager ? 'Decide the next settlement' : 'Assess the next claim',
                    description: 'The one that has waited longest',
                    to: `/staff/claims/${next.claimId}`,
                  },
                ]
              : []),
            { label: 'Unassessed claims', description: 'Registered, no assessor yet', to: '/staff/claims?status=REGISTERED' },
            { label: 'Approved, not yet paid', description: 'Payouts still to make', to: '/staff/claims?status=APPROVED' },
          ]}
          absent={['SLA deadlines on claims', 'Assigning a claim to an assessor']}
        />
      </div>
    </div>
  );
}
