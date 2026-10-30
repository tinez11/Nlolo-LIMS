import { CircleCheckBig, ClipboardPlus, FileText, Mail, Package, ScrollText, Wallet, type LucideIcon } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getCustomerDashboard, getMe, type CustomerDashboardView } from '@/api/portal';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';
import { PolicyCard } from './CustomerUi';
import { money } from './customerText';
import { useWords } from './words';

/**
 * The signed-in customer's home (2026-10-08, the customer portal design step 2; refreshed 2026-10-29 at the user's ask
 * for screens customers find appealing). What needs them leads -- the next premium, or that they are paid up -- then
 * four things they can do, then their policies as cards. Every figure comes from `/customer/dashboard`, built for the
 * token's own party; a card appears only when it applies.
 */
export function CustomerHomePage() {
  const words = useWords();
  const w = words.portal.home;
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
  const late = next?.status === 'IN_GRACE' || next?.status === 'OVERDUE';
  const quick: [string, string, LucideIcon][] = [
    [w.pay, '/customers/pay', Wallet], [w.getCover, '/customers/products', Package],
    [w.report, '/customers/claims/new', ClipboardPlus], [w.documents, '/customers/documents', FileText],
  ];
  return (
    <div className="w-full max-w-3xl space-y-6 px-4 pt-6 pb-10 text-base sm:px-6">
      <header className="space-y-1">
        <h1 className="text-headline">{w.welcome(dashboard.displayName.split(' ')[0] ?? dashboard.displayName)}</h1>
        <p className="text-muted-foreground">{w.lead}</p>
      </header>

      {/* What needs them: the next premium on the answer band, or the good news that nothing is due. */}
      {next ? (
        <section aria-label={w.nextPremium} className="hero-scope flex flex-wrap items-end justify-between gap-4 rounded-2xl bg-hero p-5">
          <div>
            <p className="text-sm text-muted-foreground">{w.nextPremium} · {next.productName ?? next.policyNumber}</p>
            <p className="text-headline tabular-nums">{money(next.amount, next.currency)}</p>
            <p className="text-sm text-muted-foreground">{w.dueOn(formatDate(next.dueDate))}</p>
            {late && <p className="mt-2 inline-block rounded-lg bg-status-warning-bg px-2.5 py-1 text-sm font-semibold text-status-warning-fg">{w.inGrace}</p>}
          </div>
          <Button asChild variant="outline" className="h-12 border-transparent bg-surface px-6 text-base font-semibold text-accent hover:bg-surface hover:opacity-90"><Link to="/customers/pay">{w.payNow}</Link></Button>
        </section>
      ) : dashboard.policies.length > 0 && (
        <section className="flex items-center gap-3 rounded-2xl border border-border bg-surface p-4 shadow-card">
          <CircleCheckBig className="size-8 shrink-0 text-status-success-fg" aria-hidden />
          <div>
            <p className="text-title">{w.allPaid}</p>
            <p className="text-sm text-muted-foreground">{w.allPaidLead}</p>
          </div>
        </section>
      )}

      <section aria-label={w.quick} className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {quick.map(([label, to, Icon]) => (
          <Link key={to} to={to}
            className="flex min-h-24 flex-col items-start justify-between gap-2 rounded-2xl border border-border bg-surface p-4 shadow-card hover:bg-hover">
            <span className="grid size-10 place-items-center rounded-xl bg-selected text-accent" aria-hidden><Icon className="size-5" /></span>
            <span className="font-semibold">{label}</span>
          </Link>
        ))}
      </section>

      {(dashboard.unreadMessages > 0 || dashboard.claimsInProgress > 0 || value) && (
        <div className="flex flex-wrap gap-2">
          {dashboard.unreadMessages > 0 && (
            <Link to="/customers/messages" className="inline-flex min-h-11 items-center gap-2 rounded-full bg-selected px-4 font-medium text-foreground">
              <Mail className="size-4 text-accent" aria-hidden />{w.messages(dashboard.unreadMessages)}
            </Link>
          )}
          {dashboard.claimsInProgress > 0 && (
            <Link to="/customers/claims" className="inline-flex min-h-11 items-center gap-2 rounded-full bg-control px-4 font-medium">
              <ScrollText className="size-4" aria-hidden />{w.claims(dashboard.claimsInProgress)}
            </Link>
          )}
          {value && (
            <span className="inline-flex min-h-11 items-center gap-2 rounded-full bg-control px-4">
              {w.savings}: <b className="tabular-nums">{money(value.amount, value.currency)}</b>
            </span>
          )}
        </div>
      )}

      <section aria-labelledby="my-policies" className="space-y-3">
        <div className="flex items-baseline justify-between gap-2">
          <h2 id="my-policies" className="text-display">{w.policies}</h2>
          {dashboard.policies.length > 2 && <Link to="/customers/policies" className="font-semibold text-accent">{w.seeAll}</Link>}
        </div>
        {dashboard.policies.length === 0 ? (
          <div className="space-y-3 rounded-2xl border border-dashed border-border-strong bg-surface p-5 text-center">
            <p className="text-title">{w.noPolicies}</p>
            <p className="text-muted-foreground">{w.noPoliciesLead}</p>
            <Button asChild variant="primary" className="h-12 px-6 text-base"><Link to="/customers/products">{w.getCover}</Link></Button>
          </div>
        ) : (
          dashboard.policies.slice(0, 3).map((p) => <PolicyCard key={p.policyNumber} policy={p} />)
        )}
      </section>
    </div>
  );
}
