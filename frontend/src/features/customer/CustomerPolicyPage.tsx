import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getCustomerPolicy, type CustomerPolicyView } from '@/api/portal';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { DepositScheduleDownload } from '@/features/documents/DepositScheduleDownload';
import { PaymentScheduleTable } from '@/features/documents/PaymentScheduleTable';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';
import { StatusPill } from './CustomerHomePage';
import { categoryText, claimStatusText, claimTypeText, money, perFrequency, roleText } from './customerText';

/**
 * One of the customer's policies (2026-10-08, the customer portal design step 2; PRD §11-12): what it covers, what it
 * costs and when, who it pays, what it is worth, and its claims. The sections follow the product -- a funeral plan's
 * family, a savings plan's balance, an investment plan's units, an annuity's income -- and the ones that do not apply
 * are left out. Read from `/customer/policies/{n}`, which refuses a policy the customer does not hold.
 */
export function CustomerPolicyPage() {
  const { policyNumber = '' } = useParams();
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

  const breadcrumb = [{ label: 'My policies', to: '/customers/policies' }];
  if (error) {
    return (
      <>
        <PageHeader breadcrumb={breadcrumb} title="Policy" />
        <div className="px-4 pt-4 sm:px-6"><ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} /></div>
      </>
    );
  }
  if (!policy) return <LoadingBlock label="Loading your policy" />;

  const s = policy.summary;
  const currency = s.currency;
  return (
    <>
      <PageHeader breadcrumb={breadcrumb} title={s.productName ?? categoryText(s.productCategory)}
        description={<span className="font-mono text-xs">{s.policyNumber}</span>} status={<StatusPill status={s.status} />} />
      <div className="space-y-4 px-4 pb-8 sm:px-6">
        <Panel title="Overview">
          <dl className="px-4 pb-2">
            {s.sumAssured != null && Number(s.sumAssured) > 0 && <Field label="Cover" value={money(s.sumAssured, currency)} emphasis />}
            {s.premium != null && <Field label="Premium" value={`${money(s.premium, currency)} ${perFrequency(s.premiumFrequency)}`} />}
            {s.nextDueDate && <Field label="Next premium due" value={`${money(s.nextDueAmount, currency)} on ${formatDate(s.nextDueDate)}`} />}
            {policy.lifeAssuredName && <Field label="Life insured" value={policy.lifeAssuredName} />}
            {policy.commencementDate && <Field label="Cover started" value={formatDate(policy.commencementDate)} />}
            {policy.maturityDate && <Field label="Ends" value={formatDate(policy.maturityDate)} />}
          </dl>
        </Panel>

        {policy.coveredLives && policy.coveredLives.length > 0 && (
          <Panel title="Who is covered" subtitle="What each death pays.">
            <ul className="divide-y divide-border">
              {policy.coveredLives.map((l, i) => (
                <li key={`${l.name}-${i}`} className="flex flex-wrap items-baseline justify-between gap-2 px-4 py-2.5 text-sm">
                  <span>
                    {l.name} <span className="text-xs text-muted-foreground">· {roleText(l.role)}</span>
                    {l.status !== 'ACTIVE' && <span className="ml-1 text-xs text-muted-foreground">(no longer covered)</span>}
                    {l.status === 'ACTIVE' && l.waitingPeriodEnds && new Date(l.waitingPeriodEnds) > new Date() && (
                      <span className="block text-xs text-subtle-foreground">
                        Waiting period to {formatDate(l.waitingPeriodEnds)} — an accident is covered before then
                      </span>
                    )}
                  </span>
                  <span className="tabular-nums">{money(l.benefit, currency)}</span>
                </li>
              ))}
            </ul>
          </Panel>
        )}

        {policy.savings && (
          <Panel title="Savings" subtitle="Your account balance today.">
            <dl className="px-4 pb-2">
              <Field label="Balance" value={money(policy.savings.balance, policy.savings.currency)} emphasis />
              {policy.savings.openedOn && <Field label="Opened" value={formatDate(policy.savings.openedOn)} />}
            </dl>
          </Panel>
        )}

        {policy.deposit && (
          <Panel title="Your deposit" subtitle="What it earns, and what you get back.">
            <dl className="px-4 pb-2">
              <Field label="Deposited" value={`${money(policy.deposit.principal, policy.deposit.currency)} on ${formatDate(policy.deposit.startDate)}`} />
              <Field label="Plan" value={`${policy.deposit.termMonths} months, ${Number(policy.deposit.ratePercent)}% for the term`} />
              <Field label="Interest earned so far" value={money(policy.deposit.interestSoFar, policy.deposit.currency)} />
              <Field label="Matures" value={formatDate(policy.deposit.maturityDate)} />
              <Field label="You get at maturity" value={money(policy.deposit.amountAtMaturity, policy.deposit.currency)} emphasis />
            </dl>
            <p className="px-4 pb-2 text-xs text-muted-foreground">
              You can close it early at any time: you get the deposit and the interest earned to that day. Contact us to close it.
            </p>
            <div className="px-4 pb-3"><DepositScheduleDownload policyNumber={s.policyNumber} /></div>
          </Panel>
        )}

        {policy.units && (
          <Panel title="Investments" subtitle="Units held, at the latest prices.">
            <dl className="px-4 pb-2">
              <Field label="Value" value={money(policy.units.totalValue, policy.units.currency)} emphasis />
            </dl>
            {policy.units.holdings.length > 0 && (
              <ul className="divide-y divide-border border-t border-border">
                {policy.units.holdings.map((h, i) => (
                  <li key={`${h.fundName}-${i}`} className="flex flex-wrap justify-between gap-2 px-4 py-2.5 text-sm">
                    <span>{h.fundName} <span className="text-xs text-muted-foreground">
                      · {h.units} units at {h.price} {h.priceDate ? `(${formatDate(h.priceDate)})` : ''}</span></span>
                    <span className="tabular-nums">{money(h.value, policy.units?.currency)}</span>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        )}

        {policy.annuity && (
          <Panel title="Income">
            <dl className="px-4 pb-2">
              <Field label="Yearly income" value={money(policy.annuity.annualIncome, policy.annuity.currency)} emphasis />
              {policy.annuity.instalment != null && (
                <Field label="Each payment" value={`${money(policy.annuity.instalment, policy.annuity.currency)} ${perFrequency(policy.annuity.frequency)}`} />
              )}
              {policy.annuity.firstPaymentDate && <Field label="First payment" value={formatDate(policy.annuity.firstPaymentDate)} />}
              {policy.annuity.guaranteedUntil && <Field label="Guaranteed until" value={formatDate(policy.annuity.guaranteedUntil)} />}
            </dl>
          </Panel>
        )}

        {s.premium != null && s.premiumFrequency !== 'SINGLE' && (
          <Panel title="Premiums" subtitle="Every premium, what was paid and when. Download it to keep.">
            <PaymentScheduleTable policyNumber={s.policyNumber} />
          </Panel>
        )}

        {policy.beneficiaries.length > 0 && (
          <Panel title="Beneficiaries" subtitle="Who is paid. To change them, contact us.">
            <ul className="divide-y divide-border">
              {policy.beneficiaries.map((b, i) => (
                <li key={`${b.name}-${i}`} className="flex justify-between gap-2 px-4 py-2.5 text-sm">
                  <span>{b.name ?? '—'}</span>
                  {b.sharePercent != null && <span className="tabular-nums">{Number(b.sharePercent)}%</span>}
                </li>
              ))}
            </ul>
          </Panel>
        )}

        <Panel title="Claims" subtitle="Claims on this policy.">
          {policy.claims.length === 0 ? (
            <p className="px-4 py-3 text-xs text-muted-foreground">No claims on this policy.</p>
          ) : (
            <ul className="divide-y divide-border">
              {policy.claims.map((c) => (
                <li key={c.claimId}>
                  <Link to={`/customers/claims/${c.claimId}`}
                    className="flex flex-wrap justify-between gap-2 px-4 py-2.5 text-sm hover:bg-hover">
                    <span>{claimTypeText(c.claimType)} · {formatDate(c.dateOfEvent)}</span>
                    <span>{claimStatusText(c.status)}{c.approvedAmount != null ? ` · ${money(c.approvedAmount, c.currency)}` : ''}</span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>
    </>
  );
}
