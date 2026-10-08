import { ChevronRight } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getCustomerDashboard, getMe, type CustomerDashboardView, type CustomerPolicySummary } from '@/api/portal';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';
import { cn } from '@/lib/cn';
import { categoryText, money, perFrequency, policyStatusText, policyStatusTone } from './customerText';

/**
 * The signed-in customer's dashboard (2026-10-08, the customer portal design step 2; PRD §9, §42). Cards appear only
 * when they apply -- no "Account value TZS 0" for someone who holds cover alone. Everything comes from
 * `/customer/dashboard`, which the server builds for the token's own party; nothing here chooses whose records.
 */
export function CustomerHomePage() {
  const [dashboard, setDashboard] = useState<CustomerDashboardView | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let live = true;
    // `/customer/me` first: it marks the invite used, which staff see on the client's Portal tab.
    getMe().then(() => getCustomerDashboard()).then(
      (d) => { if (live) { setDashboard(d); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [reload]);

  if (error) {
    return <div className="px-4 pt-6 sm:px-6"><ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} /></div>;
  }
  if (!dashboard) return <LoadingBlock label="Loading your account" />;

  const next = dashboard.nextPremium;
  const value = dashboard.accountValue;
  return (
    <>
      <PageHeader title={`Welcome, ${dashboard.displayName.split(' ')[0]}`} description="Your cover with Nlolo Life." />
      <div className="space-y-4 px-4 pb-8 sm:px-6">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Card label="Policies in force" value={String(dashboard.activePolicies)} />
          {next && (
            <Card label="Next premium" value={money(next.amount, next.currency)}
              note={`Due ${formatDate(next.dueDate)}${next.status === 'IN_GRACE' ? ' — in the grace period' : ''} · ${next.policyNumber}`}
              warn={next.status === 'IN_GRACE'} />
          )}
          {dashboard.claimsInProgress > 0 && (
            <Card label="Claims in progress" value={String(dashboard.claimsInProgress)} />
          )}
          {value && <Card label="Savings and investments" value={money(value.amount, value.currency)} note="What your plans are worth today" />}
        </div>

        <Panel title="My policies" subtitle="Open one for its cover, premiums and claims.">
          {dashboard.policies.length === 0 ? (
            <EmptyState title="No policies yet" description="Policies you hold appear here once they are issued." />
          ) : (
            <ul className="divide-y divide-border">
              {dashboard.policies.map((p) => <PolicyRow key={p.policyNumber} policy={p} />)}
            </ul>
          )}
        </Panel>
      </div>
    </>
  );
}

function Card({ label, value, note, warn = false }: { label: string; value: string; note?: string; warn?: boolean }) {
  return (
    <div className={cn('rounded-lg border bg-surface px-4 py-3', warn ? 'border-status-warning-fg/50' : 'border-border')}>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-1 text-xl font-semibold tabular-nums">{value}</p>
      {note && <p className="mt-0.5 text-xs text-subtle-foreground">{note}</p>}
    </div>
  );
}

export function PolicyRow({ policy }: { policy: CustomerPolicySummary }) {
  return (
    <li>
      <Link to={`/customers/policies/${encodeURIComponent(policy.policyNumber)}`}
        className="flex items-center gap-3 px-4 py-3 hover:bg-hover">
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium">{policy.productName ?? categoryText(policy.productCategory)}</p>
          <p className="text-xs text-muted-foreground">
            <span className="font-mono">{policy.policyNumber}</span>
            {policy.premium != null && ` · ${money(policy.premium, policy.currency)} ${perFrequency(policy.premiumFrequency)}`}
          </p>
        </div>
        <StatusPill status={policy.status} />
        <ChevronRight className="size-4 shrink-0 text-muted-foreground" aria-hidden />
      </Link>
    </li>
  );
}

export function StatusPill({ status }: { status: string }) {
  const tone = policyStatusTone(status);
  return (
    <span className={cn('shrink-0 rounded px-1.5 py-0.5 text-xs font-medium',
      tone === 'good' && 'bg-status-success-bg text-status-success-fg',
      tone === 'warn' && 'bg-status-warning-bg text-status-warning-fg',
      tone === 'plain' && 'bg-status-neutral-bg text-status-neutral-fg')}>
      {policyStatusText(status)}
    </span>
  );
}
