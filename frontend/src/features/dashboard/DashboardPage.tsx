import type { ReactElement } from 'react';
import { useAuth } from 'react-oidc-context';
import { useSearchParams } from 'react-router-dom';
import type { MonthRange } from '@/api/dashboard';
import { readIdentity } from '@/auth/claims';
import { PageHeader } from '@/components/PageHeader';
import { RecordTabs, type TabDef } from '@/components/RecordTabs';
import { EmptyState } from '@/components/states';
import type { DashboardKind } from '@/store/dashboardStore';
import { ALL_BRANCHES, branchWords, chosenBranch, useBranchRegister } from '../reporting/branchChoice';
import { ReportBranchSelect } from '../reporting/BranchSelect';
import { AgentDashboardPanel } from './AgentDashboardPanel';
import { ManagementDashboardPanel } from './ManagementDashboardPanel';
import { ClaimsDashboardPanel } from './ClaimsDashboardPanel';
import { DASHBOARD_LABEL, dashboardsFor } from './dashboards';
import { FinanceDashboardPanel } from './FinanceDashboardPanel';
import { keyFor, monthOf, rangeFor } from './range';
import { RangePicker } from './RangePicker';
import { UnderwritingDashboardPanel } from './UnderwritingDashboardPanel';

/**
 * `onRange` sets the one dashboard range -- the picker's, in the URL; a panel never keeps a second one. `branch` is the
 * one branch choice (2026-11-23, branch views): a code or `all`, in the URL beside the range.
 */
export type PanelProps = {
  range: Required<MonthRange>;
  onRange?: (range: Required<MonthRange>) => void;
  branch?: string;
};

const PANEL: Record<DashboardKind, (props: PanelProps) => ReactElement | null> = {
  underwriting: UnderwritingDashboardPanel,
  claims: ClaimsDashboardPanel,
  finance: FinanceDashboardPanel,
  agent: AgentDashboardPanel,
  overview: ManagementDashboardPanel,
};

/** The dashboards that can be cut by branch; finance stays company-wide, as the ledger does. */
const BY_BRANCH: readonly DashboardKind[] = ['overview', 'underwriting', 'claims'];

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;
const COMPARED = 'Compared with the same months a year earlier';

/**
 * Where a working role lands (2026-10-14). Replaces "Today": its work-waiting counts are now each dashboard's work
 * panel, so there is one landing page, not two.
 *
 * One tab per dashboard the person holds. Tabs, not a stack: a person holding underwriting and finance does two
 * separate jobs and reads one at a time, and an inactive tab is not mounted, so it costs no request (RecordTabs).
 * The tab lives in the URL, so a link can open on the right one.
 *
 * The range (2026-10-23) lives in the URL too, as two months, and one picker above the tabs serves them all: switching
 * tab keeps the months the reader chose. So does the branch (2026-11-23): one choice on the same row, starting on the
 * person's home branch unless it is the head office, and said in words in the comparison note -- or "Company-wide" on
 * a tab that cannot be cut by branch.
 */
export function DashboardPage({ realm }: { realm: 'staff' | 'agents' }) {
  const auth = useAuth();
  const kinds = dashboardsFor(realm, readIdentity(auth.user?.access_token));
  const [params, setParams] = useSearchParams();
  const current = monthOf(new Date());
  const register = useBranchRegister();

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

  const offersBranch = realm === 'staff' && kinds.some((k) => BY_BRANCH.includes(k));
  // Until the register says whether a home branch is the head office, the panels wait (undefined) rather than load one
  // branch and then every branch.
  const branch = !offersBranch ? undefined
    : (params.get('branch') || register.ready)
      ? chosenBranch(params.get('branch'), register.homeBranch, register.headOffice)
      : undefined;
  const setBranch = (next: string) => {
    const p = new URLSearchParams(params);
    p.set('branch', next);
    setParams(p, { replace: true });
  };

  const activeKind = (params.get('dashboard') as DashboardKind | null) ?? kinds[0];
  const words = branch ? branchWords(branch, register.options) : null;
  const compareText = !words ? COMPARED
    : activeKind && BY_BRANCH.includes(activeKind) ? `${words} · ${COMPARED}`
    : `Company-wide · ${COMPARED}`;

  const tabs: TabDef[] = kinds.map((kind) => {
    const Panel = PANEL[kind];
    const panelBranch = BY_BRANCH.includes(kind) ? branch : undefined;
    const waiting = offersBranch && BY_BRANCH.includes(kind) && branch === undefined;
    return {
      value: kind,
      label: DASHBOARD_LABEL[kind],
      content: waiting ? null : <Panel range={range} onRange={setRange} {...(panelBranch ? { branch: panelBranch } : {})} />,
    };
  });

  return (
    <>
      <PageHeader title="Dashboard" />
      <div className="space-y-5 px-6 pt-1 pb-8">
        {kinds.length > 0 && (
          <RangePicker
            value={{ key: keyFor(range, current), range }}
            current={current}
            onChange={setRange}
            compareText={compareText}
            {...(offersBranch
              ? {
                  scope: (
                    <ReportBranchSelect
                      layout="inline"
                      value={branch ?? ALL_BRANCHES}
                      options={register.options}
                      onChange={setBranch}
                    />
                  ),
                }
              : {})}
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
