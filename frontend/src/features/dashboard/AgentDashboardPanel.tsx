import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { KpiRow, type Kpi } from '@/components/KpiCard';
import { Panel } from '@/components/Panel';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { countValue } from '@/lib/counts';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectAgent, useDashboardStore } from '@/store/dashboardStore';
import { DashboardRail } from './DashboardRail';

const MONTH = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];

/**
 * The agent's dashboard (2026-10-14), and their landing page. Scoped by the server to the clients they registered and
 * their team's book. Leads with KYC because a client whose identity is unverified cannot hold a policy, and the
 * documents are the one part of that the agent can move.
 */
export function AgentDashboardPanel() {
  const resource = useDashboardStore(selectAgent);
  const load = useDashboardStore((s) => s.load);

  useEffect(() => {
    void load('agent');
  }, [load]);

  if (isInitialLoad(resource)) return <LoadingBlock label="Counting your clients" />;
  if (resource.status === 'error' && resource.error && resource.data === null) {
    return <ErrorPanel error={resource.error} onRetry={() => void load('agent')} />;
  }
  const v = resource.data;
  if (!v) return null;

  const period = MONTH[Number(v.commissionPeriod.slice(5, 7)) - 1] ?? v.commissionPeriod;
  const kpis: Kpi[] = [
    { label: 'My clients', value: countValue(v.clients), hint: 'registered by you', to: '/agents/clients' },
    {
      label: 'Clients awaiting KYC',
      value: countValue(v.clientsAwaitingKyc),
      hint: 'documents not yet verified',
      to: '/agents/clients?kycStatus=PENDING',
    },
    {
      label: 'Policies in my book',
      value: countValue(v.policiesInBook),
      hint: 'you and your downline',
      to: '/agents/policies',
    },
    {
      label: 'Applications in underwriting',
      value: countValue(v.applicationsInUnderwriting),
      hint: 'your clients, not yet decided',
    },
    {
      label: `Commission, ${period}`,
      value: v.commissionEarned ? formatMoney(v.commissionEarned) : null,
      hint: 'last closed statement',
      to: '/agents/commission',
    },
  ];

  return (
    <div className="space-y-5">
      <KpiRow kpis={kpis} />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <Panel
          emphasis
          title="Clients still needing KYC documents"
          subtitle="Send the ID scan; the office decides. Newest first."
        >
          {v.awaitingKyc.length === 0 ? (
            <p className="px-4 pb-4 text-xs text-muted-foreground">Every client you registered has been verified.</p>
          ) : (
            <ul className="divide-y divide-border">
              {v.awaitingKyc.map((c) => (
                <li key={c.partyId} className="flex items-center gap-3 px-4 py-2.5">
                  <span className="min-w-0 flex-1 truncate text-sm">{c.displayName}</span>
                  <StatusBadge kind="kyc" value={c.kycStatus} />
                  <Link
                    to={`/agents/parties/${c.partyId}?tab=kyc`}
                    aria-label={`Send documents for ${c.displayName}`}
                    className="rounded-md px-2 py-1 text-sm font-medium hover:bg-hover"
                  >
                    Send documents
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Panel>
        <DashboardRail
          alerts={[
            ...(v.clientsAwaitingKyc
              ? [
                  {
                    tone: 'warning' as const,
                    text: (
                      <>
                        <b>{countValue(v.clientsAwaitingKyc)} clients</b> cannot hold a policy until their KYC is
                        verified.
                      </>
                    ),
                  },
                ]
              : []),
            ...(v.clientsKycRejected
              ? [
                  {
                    tone: 'danger' as const,
                    text: (
                      <>
                        <b>
                          {countValue(v.clientsKycRejected)}{' '}
                          {v.clientsKycRejected === 1 ? 'client has' : 'clients have'}
                        </b>{' '}
                        had documents rejected.{' '}
                        <Link to="/agents/clients?kycStatus=REJECTED" className="underline">
                          Send clearer ones.
                        </Link>
                      </>
                    ),
                  },
                ]
              : []),
          ]}
          actions={[
            { label: 'Register a client', description: 'Then send their ID documents', to: '/agents/customers/new' },
            { label: 'My clients', description: 'Everyone you have registered', to: '/agents/clients' },
            { label: 'My policies', description: 'Your book and your downline’s', to: '/agents/policies' },
            { label: 'My commission', description: 'Statements and payouts', to: '/agents/commission' },
          ]}
          absent={['Quotes', 'Leads and follow-up reminders']}
        />
      </div>
    </div>
  );
}
