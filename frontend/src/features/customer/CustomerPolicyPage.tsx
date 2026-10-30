import { ChevronDown, ChevronRight, ClipboardPlus } from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getCustomerPolicy, type CustomerPolicyView } from '@/api/portal';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { DepositScheduleDownload } from '@/features/documents/DepositScheduleDownload';
import { PaymentScheduleDownloads, PaymentScheduleTable } from '@/features/documents/PaymentScheduleTable';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';
import { StatusPill } from './CustomerUi';
import { money } from './customerText';
import { useWords } from './words';

/**
 * One of the customer's policies (2026-10-08, the customer portal design step 2; PRD §11-12; refreshed 2026-10-29). The
 * figure it exists for leads on the answer band -- the cover, a savings balance, what a deposit pays at maturity, an
 * income -- with the next payment and Pay now beside it. The sections follow the product and the ones that do not apply
 * are left out; the payment history folds away so it never runs off a phone. Read from `/customer/policies/{n}`, which
 * refuses a policy the customer does not hold.
 */
export function CustomerPolicyPage() {
  const { policyNumber = '' } = useParams();
  const words = useWords();
  const w = words.portal.policy;
  const [policy, setPolicy] = useState<CustomerPolicyView | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let live = true;
    getCustomerPolicy(policyNumber).then(
      (p) => { if (live) { setPolicy(p); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [policyNumber, reload]);

  const breadcrumb = [{ label: words.portal.policies.title, to: '/customers/policies' }];
  if (error) {
    return (
      <>
        <PageHeader breadcrumb={breadcrumb} title={words.portal.policies.title} />
        <div className="px-4 pt-4 sm:px-6"><ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} /></div>
      </>
    );
  }
  if (!policy) return <LoadingBlock label="Loading your policy" />;

  const s = policy.summary;
  const currency = s.currency;
  const per = (f: string | null | undefined) => words.portal.per[f ?? ''] ?? '';
  // The figure the policy exists for, first one that applies.
  const lead: [string, string] | null = policy.savings ? [w.worth, money(policy.savings.balance, policy.savings.currency)]
    : policy.deposit ? [w.atMaturity, money(policy.deposit.amountAtMaturity, policy.deposit.currency)]
      : policy.units ? [w.worth, money(policy.units.totalValue, policy.units.currency)]
        : policy.annuity ? [w.income, money(policy.annuity.annualIncome, policy.annuity.currency)]
          : s.sumAssured != null && Number(s.sumAssured) > 0 ? [w.cover, money(s.sumAssured, currency)] : null;

  return (
    <>
      <PageHeader breadcrumb={breadcrumb} title={s.productName ?? (s.productCategory ? words.portal.category[s.productCategory] : '') ?? s.policyNumber}
        description={<span className="font-mono text-xs">{s.policyNumber}</span>} status={<StatusPill status={s.status} />} />
      <div className="w-full max-w-3xl space-y-4 px-4 pb-10 text-base sm:px-6">
        <section className="hero-scope grid gap-4 rounded-2xl bg-hero p-5 sm:grid-cols-2">
          {lead && (
            <div>
              <p className="text-sm text-muted-foreground">{lead[0]}</p>
              <p className="text-headline tabular-nums">{lead[1]}</p>
            </div>
          )}
          {s.premium != null && (
            <div>
              <p className="text-sm text-muted-foreground">{w.youPay}</p>
              <p className="text-display tabular-nums">{money(s.premium, currency)} <span className="text-base font-normal">{per(s.premiumFrequency)}</span></p>
              {s.nextDueDate && (
                <p className="text-sm text-muted-foreground">{w.nextDue}: {money(s.nextDueAmount, currency)} {w.on(formatDate(s.nextDueDate))}</p>
              )}
            </div>
          )}
          {s.nextDueDate && (
            <div className="sm:col-span-2">
              <Button asChild variant="outline" className="h-12 border-transparent bg-surface px-6 text-base font-semibold text-accent hover:bg-surface hover:opacity-90"><Link to="/customers/pay">{w.pay}</Link></Button>
            </div>
          )}
        </section>

        <Card title={w.details}>
          <Rows rows={[
            [w.reference, <span key="n" className="font-mono">{s.policyNumber}</span>],
            ...(policy.lifeAssuredName ? [[w.lifeInsured, policy.lifeAssuredName] as Row] : []),
            ...(policy.commencementDate ? [[w.started, formatDate(policy.commencementDate)] as Row] : []),
            ...(policy.maturityDate ? [[w.ends, formatDate(policy.maturityDate)] as Row] : []),
          ]} />
        </Card>

        {policy.coveredLives && policy.coveredLives.length > 0 && (
          <Card title={w.who} lead={w.whoLead}>
            <ul className="divide-y divide-border">
              {policy.coveredLives.map((l, i) => (
                <li key={`${l.name}-${i}`} className="flex flex-wrap items-baseline justify-between gap-2 py-3">
                  <span>
                    <span className="font-medium">{l.name}</span>{' '}
                    <span className="text-sm text-muted-foreground">· {words.portal.role[l.role] ?? l.role}</span>
                    {l.status !== 'ACTIVE' && <span className="ml-1 text-sm text-muted-foreground">({w.noLonger})</span>}
                    {l.status === 'ACTIVE' && l.waitingPeriodEnds && new Date(l.waitingPeriodEnds) > new Date() && (
                      <span className="block text-sm text-status-warning-fg">{w.waiting(formatDate(l.waitingPeriodEnds))}</span>
                    )}
                  </span>
                  <span className="font-semibold tabular-nums">{money(l.benefit, currency)}</span>
                </li>
              ))}
            </ul>
          </Card>
        )}

        {policy.savings && (
          <Card title={w.savings}>
            <Rows rows={[
              [w.balance, money(policy.savings.balance, policy.savings.currency)],
              ...(policy.savings.openedOn ? [[w.opened, formatDate(policy.savings.openedOn)] as Row] : []),
            ]} />
          </Card>
        )}

        {policy.deposit && (
          <Card title={w.depositTitle}>
            <Rows rows={[
              [w.deposited, `${money(policy.deposit.principal, policy.deposit.currency)} · ${formatDate(policy.deposit.startDate)}`],
              [w.plan, w.planText(policy.deposit.termMonths, String(Number(policy.deposit.ratePercent)))],
              [w.interestSoFar, money(policy.deposit.interestSoFar, policy.deposit.currency)],
              [w.matures, formatDate(policy.deposit.maturityDate)],
              [w.atMaturity, money(policy.deposit.amountAtMaturity, policy.deposit.currency)],
            ]} />
            <p className="pt-2 text-sm text-muted-foreground">{w.closeEarly}</p>
            <div className="pt-2"><DepositScheduleDownload policyNumber={s.policyNumber} /></div>
          </Card>
        )}

        {policy.units && (
          <Card title={w.investments}>
            <ul className="divide-y divide-border">
              {policy.units.holdings.map((h, i) => (
                <li key={`${h.fundName}-${i}`} className="flex flex-wrap justify-between gap-2 py-3">
                  <span>{h.fundName} <span className="block text-sm text-muted-foreground">
                    {w.units(String(h.units), String(h.price))} {h.priceDate ? `(${formatDate(h.priceDate)})` : ''}</span></span>
                  <span className="font-semibold tabular-nums">{money(h.value, policy.units?.currency)}</span>
                </li>
              ))}
            </ul>
          </Card>
        )}

        {policy.annuity && (
          <Card title={w.incomeTitle}>
            <Rows rows={[
              ...(policy.annuity.instalment != null
                ? [[w.each, `${money(policy.annuity.instalment, policy.annuity.currency)} ${per(policy.annuity.frequency)}`] as Row] : []),
              ...(policy.annuity.firstPaymentDate ? [[w.first, formatDate(policy.annuity.firstPaymentDate)] as Row] : []),
              ...(policy.annuity.guaranteedUntil ? [[w.guaranteed, formatDate(policy.annuity.guaranteedUntil)] as Row] : []),
            ]} />
          </Card>
        )}

        {policy.beneficiaries.length > 0 && (
          <Card title={w.beneficiaries} lead={w.beneficiariesLead}>
            <ul className="divide-y divide-border">
              {policy.beneficiaries.map((b, i) => (
                <li key={`${b.name}-${i}`} className="flex justify-between gap-2 py-3">
                  <span className="font-medium">{b.name ?? '—'}</span>
                  {b.sharePercent != null && <span className="tabular-nums">{Number(b.sharePercent)}%</span>}
                </li>
              ))}
            </ul>
          </Card>
        )}

        {s.premium != null && s.premiumFrequency !== 'SINGLE' && <PaymentHistory policyNumber={s.policyNumber} />}

        <Card title={w.claims} action={
          <Button asChild variant="outline" className="h-11"><Link to="/customers/claims/new"><ClipboardPlus aria-hidden />{w.report}</Link></Button>
        }>
          {policy.claims.length === 0 ? (
            <p className="text-muted-foreground">{w.noClaims}</p>
          ) : (
            <ul className="divide-y divide-border">
              {policy.claims.map((c) => (
                <li key={c.claimId}>
                  <Link to={`/customers/claims/${c.claimId}`} className="flex items-center justify-between gap-2 py-3 hover:text-accent">
                    <span>{words.portal.claimType[c.claimType] ?? c.claimType} · {formatDate(c.dateOfEvent)}</span>
                    <span className="flex items-center gap-1 text-sm">
                      {words.portal.claimStatus[c.status] ?? c.status}{c.approvedAmount != null ? ` · ${money(c.approvedAmount, c.currency)}` : ''}
                      <ChevronRight className="size-4 text-muted-foreground" aria-hidden />
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </>
  );
}

type Row = [string, ReactNode];

function Card({ title, lead, action, children }: { title: string; lead?: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded-2xl border border-border bg-surface p-4 shadow-card sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-2 pb-2">
        <div>
          <h2 className="text-title">{title}</h2>
          {lead && <p className="text-sm text-muted-foreground">{lead}</p>}
        </div>
        {action}
      </div>
      {children}
    </section>
  );
}

function Rows({ rows }: { rows: Row[] }) {
  return (
    <dl className="divide-y divide-border">
      {rows.map(([label, value]) => (
        <div key={label} className="flex flex-wrap justify-between gap-x-4 gap-y-0.5 py-2.5">
          <dt className="text-muted-foreground">{label}</dt>
          <dd className="text-right font-medium tabular-nums">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Folded by default: a table of every premium is wider than a phone, and most visits are not for it. */
function PaymentHistory({ policyNumber }: { policyNumber: string }) {
  const w = useWords().portal.policy;
  const [open, setOpen] = useState(false);
  return (
    <section className="rounded-2xl border border-border bg-surface shadow-card">
      <button type="button" aria-expanded={open} onClick={() => setOpen(!open)}
        className="flex w-full items-center justify-between gap-2 p-4 text-left sm:p-5">
        <span>
          <span className="block text-title">{w.payments}</span>
          <span className="block text-sm text-muted-foreground">{w.paymentsLead}</span>
        </span>
        <ChevronDown className={`size-5 shrink-0 transition-transform ${open ? 'rotate-180' : ''}`} aria-hidden />
      </button>
      {open && (
        <div className="space-y-2 border-t border-border pb-2">
          <div className="px-4 pt-3 sm:px-5"><PaymentScheduleDownloads policyNumber={policyNumber} /></div>
          <div className="overflow-x-auto"><PaymentScheduleTable policyNumber={policyNumber} /></div>
        </div>
      )}
    </section>
  );
}
