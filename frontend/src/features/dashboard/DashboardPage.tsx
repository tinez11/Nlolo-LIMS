import type { ReactElement } from 'react';
import { useAuth } from 'react-oidc-context';
import { useSearchParams } from 'react-router-dom';
import type { MonthRange } from '@/api/dashboard';
import { readIdentity } from '@/auth/claims';
import { PageHeader } from '@/components/PageHeader';
import { RecordTabs, type TabDef } from '@/components/RecordTabs';
import { EmptyState } from '@/components/states';
import type { DashboardKind } from '@/store/dashboardStore';
import { AgentDashboardPanel } from './AgentDashboardPanel';
import { ClaimsDashboardPanel } from './ClaimsDashboardPanel';
import { DASHBOARD_LABEL, dashboardsFor } from './dashboards';
import { FinanceDashboardPanel } from './FinanceDashboardPanel';
import { keyFor, monthOf, rangeFor } from './range';
import { RangePicker } from './RangePicker';
import { UnderwritingDashboardPanel } from './UnderwritingDashboardPanel';

export type PanelProps = { range: Required<MonthRange> };

const PANEL: Record<DashboardKind, (props: PanelProps) => ReactElement | null> = {
  underwriting: UnderwritingDashboardPanel,
  claims: ClaimsDashboardPanel,
  finance: FinanceDashboardPanel,
  agent: AgentDashboardPanel,
};

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

/**
 * Where a working role lands (2026-10-14). Replaces "Today": its work-waiting counts are now each dashboard's work
 * panel, so there is one landing page, not two.
 *
 * One tab per dashboard the person holds. Tabs, not a stack: a person holding underwriting and finance does two
 * separate jobs and reads one at a time, and an inactive tab is not mounted, so it costs no request (RecordTabs).
 * The tab lives in the URL, so a link can open on the right one.
 *
 * The range (2026-10-23) lives in the URL too, as two months, and one picker above the tabs serves them all: switching
 * tab keeps the months the reader chose.
 */
export function DashboardPage({ realm }: { realm: 'staff' | 'agents' }) {
  const auth = useAuth();
  const kinds = dashboardsFor(realm, readIdentity(auth.user?.access_token));
  const [params, setParams] = useSearchParams();
  const current = monthOf(new Date());

  const from = params.get('from');
  const to = params.get('to');
  const range: Required<MonthRange> = from && MONTH.test(from) && to && MONTH.test(to) && from <= to && to <= current
    ? { from, to }
    : rangeFor('ytd', current);

  const setRange = (next: Required<MonthRange>) => {
    const p = new URLSearchParams(params);
    p.set('from', next.from);
    p.set('to', next.to);
    setParams(p, { replace: true });
  };

  const tabs: TabDef[] = kinds.map((kind) => {
    const Panel = PANEL[kind];
    return { value: kind, label: DASHBOARD_LABEL[kind], content: <Panel range={range} /> };
  });

  return (
    <>
      <PageHeader title="Dashboard" description="What is waiting for you, and the figures behind your work." />
      <div className="space-y-5 px-6 pt-1 pb-8">
        {kinds.length > 0 && (
          <RangePicker
            value={{ key: keyFor(range, current), range }}
            current={current}
            onChange={setRange}
            compareText="Compared with the same months a year earlier"
          />
        )}
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
