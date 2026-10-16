import type { ReactElement } from 'react';
import { useAuth } from 'react-oidc-context';
import { readIdentity } from '@/auth/claims';
import { PageHeader } from '@/components/PageHeader';
import { RecordTabs, type TabDef } from '@/components/RecordTabs';
import { EmptyState } from '@/components/states';
import type { DashboardKind } from '@/store/dashboardStore';
import { AgentDashboardPanel } from './AgentDashboardPanel';
import { ClaimsDashboardPanel } from './ClaimsDashboardPanel';
import { DASHBOARD_LABEL, dashboardsFor } from './dashboards';
import { FinanceDashboardPanel } from './FinanceDashboardPanel';
import { UnderwritingDashboardPanel } from './UnderwritingDashboardPanel';

const PANEL: Record<DashboardKind, () => ReactElement | null> = {
  underwriting: UnderwritingDashboardPanel,
  claims: ClaimsDashboardPanel,
  finance: FinanceDashboardPanel,
  agent: AgentDashboardPanel,
};

/**
 * Where a working role lands (2026-10-14). Replaces "Today": its work-waiting counts are now each dashboard's work
 * panel, so there is one landing page, not two.
 *
 * One tab per dashboard the person holds. Tabs, not a stack: a person holding underwriting and finance does two
 * separate jobs and reads one at a time, and an inactive tab is not mounted, so it costs no request (RecordTabs).
 * The tab lives in the URL, so a link can open on the right one.
 */
export function DashboardPage({ realm }: { realm: 'staff' | 'agents' }) {
  const auth = useAuth();
  const kinds = dashboardsFor(realm, readIdentity(auth.user?.access_token));

  const tabs: TabDef[] = kinds.map((kind) => {
    const Panel = PANEL[kind];
    return { value: kind, label: DASHBOARD_LABEL[kind], content: <Panel /> };
  });

  return (
    <>
      <PageHeader title="Dashboard" description="What is waiting for you, and the figures behind your work." />
      <div className="px-6 pt-1 pb-8">
        {kinds.length === 0 ? (
          <EmptyState title="No dashboard for your role" description="None of your roles has figures to show here yet." />
        ) : kinds.length === 1 ? (
          tabs[0]?.content
        ) : (
          <RecordTabs tabs={tabs} label="Dashboards" param="dashboard" />
        )}
      </div>
    </>
  );
}
